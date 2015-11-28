/*
 *  Copyright 2006-2015 WebPKI.org (http://webpki.org).
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.webpki.tapconnect;

import java.awt.Container;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Toolkit;
import java.awt.Insets;

import java.awt.event.WindowEvent;
import java.awt.event.WindowAdapter;

import java.awt.image.BufferedImage;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;

import java.util.Enumeration;
import java.util.Vector;

import java.util.concurrent.Executors;

import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.logging.SimpleFormatter;

import javax.imageio.ImageIO;

import javax.swing.ImageIcon;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.webpki.json.JSONObjectReader;
import org.webpki.json.JSONObjectWriter;
import org.webpki.json.JSONOutputFormats;
import org.webpki.json.JSONParser;

import org.webpki.util.ArrayUtil;

import static org.webpki.tapconnect.TapConnectProperties.*;

// A very prototype-ish NFCLauncher using HTTP/WiFi rather than BLE but
// it at least works for demonstrating the navigator.tapConnect() concept :-)

class StdoutJSONPipe {

    String writeJSONObject (JSONObjectWriter ow) throws IOException {
        byte[] utf8 = ow.serializeJSONObject(JSONOutputFormats.NORMALIZED);
        int l = utf8.length;
        // Code only works for little-endian machines
        // Network order, heard of that Google?
        byte[] blob = new byte[l + 4];
        blob[0] = (byte) l;
        blob[1] = (byte) (l >>> 8);
        blob[2] = (byte) (l >>> 16);
        blob[3] = (byte) (l >>> 24);
        for (int i = 0; i < l; i++) {
            blob[4 + i] = utf8[i];
        }
        System.out.write(blob);
        return new String(utf8, "UTF-8");
    }
}

class StdinJSONPipe {
    
    String jsonString;
    
    DataInputStream dis;
    
    StdinJSONPipe() {
        dis = new DataInputStream(System.in);
    }

    JSONObjectReader readJSONObject() throws IOException {
        byte[] byteBuffer = new byte[4];
        dis.readFully(byteBuffer, 0, 4);
        // Code only works for little-endian machines
        // Network order, heard of that Google?
        int l = (byteBuffer[3]) << 24 | (byteBuffer[2] & 0xff) << 16 |
                (byteBuffer[1] & 0xff) << 8 | (byteBuffer[0] & 0xff);
        if (l > 100000)
            System.exit(3);
        byte[] utf8 = new byte[l];
        dis.readFully(utf8);
        jsonString = new String(utf8, "UTF-8");
        return JSONParser.parse(utf8);
    }

    String getJSONString () {
        return jsonString;
    }
}

public class NFCLauncher extends Thread {
    
    static Logger logger = Logger.getLogger("MyLog");

    static String application;
    static String invocationUrl;
    static String optionalData;
    
    static String ipAddress;
    
    static ImageIcon nfcLogo;
    JLabel nfcIconLabel;

    static ImageIcon connectedIcon;

    StdinJSONPipe stdin = new StdinJSONPipe();
    StdoutJSONPipe stdout = new StdoutJSONPipe();
    boolean initMode = true;

    class Synchronizer {

        boolean touched;
        boolean timeout_flag;
        JSONObjectReader json;

        synchronized boolean perform(int timeout) {
            while (!touched && !timeout_flag) {
                try {
                    wait(timeout);
                } catch (InterruptedException e) {
                    return false;
                }
                timeout_flag = true;
            }
            return touched;
        }

        synchronized void haveData4You(JSONObjectReader json) {
            this.json = json;
            touched = true;
            notify();
        }
    }
    
    Synchronizer synchronizer;

    synchronized Synchronizer getSynchronizer (boolean inserter) {
        if (inserter && synchronizer != null && synchronizer.json != null) {
            logger.severe("Overrrun!");
            System.exit(3);
        }
        if (synchronizer != null && !synchronizer.timeout_flag) {
            Synchronizer temp = synchronizer;
            synchronizer = null;
            return temp;
        }
        return synchronizer = new Synchronizer();
    }

    class RequestHandler implements HttpHandler {
        
        public void handle(HttpExchange exchange) throws IOException {
            if (exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                JSONObjectReader request = JSONParser.parse(ArrayUtil.getByteArrayFromInputStream(exchange.getRequestBody()));
                JSONObjectWriter response = new JSONObjectWriter();
                logger.info(request.toString());
                if (request.hasProperty(CONTROL_JSON)) {
                    if (request.getBoolean(CONTROL_JSON)) {
                        Synchronizer sync = getSynchronizer(false);
                        if (sync.perform(BACK_CHANNEL_TIMEOUT / 2)) {
                            response = new JSONObjectWriter(sync.json);
                        } else {
                            response.setBoolean(NOTHING_JSON, true);
                        }
                    } else {
                        stdout.writeJSONObject(new JSONObjectWriter(request.getObject(MESSAGE_JSON)));
                    }
                } else {
                    if (initMode) {
                        stdout.writeJSONObject(new JSONObjectWriter().setBoolean("@connect@", true));
                        response.setString(APPLICATION_JSON, application);
                        response.setString(INVOCATION_URL_JSON, invocationUrl);
                        response.setString(OPTIONAL_DATA_JSON, optionalData);
                        initMode = false;
                        SwingUtilities.invokeLater(new Runnable() {
                            @Override
                            public void run() {
                                nfcIconLabel.setIcon(connectedIcon);
                            }
                        });
                    } else {
                        System.exit(3);
                    }
                }
                Headers responseHeaders = exchange.getResponseHeaders();
                responseHeaders.set("Content-Type", JSON_CONTENT_TYPE);
                byte[] json = response.serializeJSONObject(JSONOutputFormats.NORMALIZED);
                exchange.sendResponseHeaders(200, json.length);
                OutputStream os = exchange.getResponseBody();
                os.write(json);
                exchange.close();
             }
        }
    }

    NFCLauncher(Container pane) {
        // Start by initializing the HTTP server
        try {
            final HttpServer server = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
            server.createContext("/", new RequestHandler());
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            logger.info("Server is listening on port " + HTTP_PORT);
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    server.stop(0);
                }
            });
        } catch (Exception e) {
            logger.log(Level.SEVERE, "HTTP Server error", e);
            System.exit(3);
        }

        // Then initialize the GUI
        int fontSize = Toolkit.getDefaultToolkit().getScreenResolution() / 7;
        JLabel urlMessageLabel = new JLabel("Invocation URL:");
        Font font = urlMessageLabel.getFont();
        boolean macOS = System.getProperty("os.name").toLowerCase().contains("mac");
        if (font.getSize() > fontSize || macOS) {
            fontSize = font.getSize();
        }
        Font stdFont = new Font(font.getFontName(), font.getStyle(), fontSize);
        int stdInset = fontSize/3;
        urlMessageLabel.setFont(stdFont);
        pane.setLayout(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridwidth = 1;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.fill = GridBagConstraints.NONE;
        c.gridx = 0;
        c.gridy = 0;
        c.insets = new Insets(stdInset, stdInset, stdInset, stdInset);
        pane.add(urlMessageLabel, c);

        JLabel urlLabel = new JLabel(ipAddress);
        urlLabel.setFont(stdFont);
        c.gridy = 1;
        c.insets = new Insets(0, stdInset * 2, 0, stdInset);
        pane.add(urlLabel, c);

        nfcIconLabel = new JLabel(nfcLogo);
        c.weighty = 1.0;
        c.gridy = 2;
        c.fill = GridBagConstraints.BOTH;
        c.anchor = GridBagConstraints.CENTER;
        c.insets = new Insets(0, fontSize * 3, 0, fontSize * 3);
        pane.add(nfcIconLabel, c);
    }

    @Override
    public void run() {
        while (true) {
            try {
                JSONObjectReader webdata = stdin.readJSONObject();  // Hanging until there is something
                getSynchronizer(true).haveData4You(webdata);        // Yay!
            } catch (IOException e) {
                try {
                    // Final effort telling the world that we are closing for today...
                    getSynchronizer(true).haveData4You(JSONParser.parse(new JSONObjectWriter().setBoolean(CLOSE_JSON, true).toString()));
                } catch (IOException e1) {
                }        
                logger.log(Level.SEVERE, "Reading", e);
                System.exit(3);
            }
        }
    }

    static BufferedImage getIcon(String name) throws IOException {
        return ImageIO.read(NFCLauncher.class.getResourceAsStream(name));
    }

    static ImageIcon getImageIcon(String name) throws IOException {
        return new ImageIcon(ArrayUtil.getByteArrayFromInputStream(NFCLauncher.class.getResourceAsStream(name)));
    }

    public static void main(String[] args) {
        Vector<BufferedImage> icons = new Vector<BufferedImage>();
        try {
            logger.setUseParentHandlers(false);
            FileHandler fh = new FileHandler(args[0] + File.separator + "logs" + File.separator + "nfc-launcher.log");
            logger.addHandler(fh);
            SimpleFormatter formatter = new SimpleFormatter();
            fh.setFormatter(formatter);
            for (int i = 0; i < args.length; i++) {
                logger.info("ARG[" + i + "]=" + args[i]);
            }
            application = args[1];
            invocationUrl = args[2];
            optionalData = args[3];
            icons.add(getIcon("nfc32.png"));
            icons.add(getIcon("nfc64.png"));
            nfcLogo = getImageIcon("nfc-logo-vector.png");
            connectedIcon = getImageIcon("loading-gears-animation-3.gif");
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            int foundAddresses = 0;
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                if (networkInterface.isUp() && !networkInterface.isVirtual() && !networkInterface.isLoopback() &&
                    networkInterface.getDisplayName().indexOf("VMware") < 0) {  // Well....
                    Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                    while (inetAddresses.hasMoreElements()) {
                        InetAddress inetAddress = inetAddresses.nextElement();
                        if (inetAddress instanceof Inet4Address) {
                            foundAddresses++;
                            ipAddress = "http://" + inetAddress.getHostAddress() + ":" + HTTP_PORT;
                        }
                    }
                }
            }
            if (application.equals("webauth.demo")) {
                ipAddress = JSONParser.parse(org.webpki.util.Base64URL.decode(optionalData)).getString("callme");
            }
            if (foundAddresses != 1) {
                throw new IOException("Couldn't determine network interface!'");
            }
            final Process nfc = Runtime.getRuntime().exec(new String[]{args[0] + File.separator + "NFCWriter.exe",
                                                                       ipAddress});
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    nfc.destroy();
                }
            });
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Initialization failed", e);
            System.exit(3);
        }
        JFrame frame = new JFrame("NFC Launcher");
        frame.setIconImages(icons);
        NFCLauncher md = new NFCLauncher(frame.getContentPane());
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setAlwaysOnTop(true);

        frame.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                System.exit(0);
            }
        });
        frame.setExtendedState(frame.getExtendedState() | JFrame.ICONIFIED);
        frame.setVisible(true);
        md.start();
    }
}
