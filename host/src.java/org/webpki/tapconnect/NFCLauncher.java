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
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowAdapter;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.Executors;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.logging.SimpleFormatter;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.webpki.json.JSONObjectReader;
import org.webpki.json.JSONObjectWriter;
import org.webpki.json.JSONOutputFormats;
import org.webpki.json.JSONParser;
import org.webpki.util.ArrayUtil;
import org.webpki.util.ISODateTime;

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
    
    static final int HTTP_PORT = 8099;

    StdinJSONPipe stdin = new StdinJSONPipe();
    StdoutJSONPipe stdout = new StdoutJSONPipe();
    JTextArea textArea;
    JTextField sendText;

    class RequestHandler implements HttpHandler {
        
        public void handle(HttpExchange exchange) throws IOException {
            String requestMethod = exchange.getRequestMethod();
            if (requestMethod.equalsIgnoreCase("GET")) {
                Headers responseHeaders = exchange.getResponseHeaders();
                responseHeaders.set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, 0);

                OutputStream responseBody = exchange.getResponseBody();
                Headers requestHeaders = exchange.getRequestHeaders();
                Set<String> keySet = requestHeaders.keySet();
                Iterator<String> iter = keySet.iterator();
                while (iter.hasNext()) {
                    String key = iter.next();
                    List values = requestHeaders.get(key);
                    String s = key + " = " + values.toString() + "\n";
                    responseBody.write(s.getBytes());
                }
                responseBody.close();
            } else if (requestMethod.equalsIgnoreCase("POST")) {
                   String request = new String(ArrayUtil.getByteArrayFromInputStream(exchange.getRequestBody()), "UTF-8");
                   NFCLauncher.logger.info(request);
                   String response = "This is the response";
                   Headers responseHeaders = exchange.getResponseHeaders();
                   responseHeaders.set("Content-Type", "text/plain");
                   exchange.sendResponseHeaders(200, response.length());
                   OutputStream os = exchange.getResponseBody();
                   os.write(response.getBytes());
                   exchange.close();
                update(stdout.writeJSONObject(new JSONObjectWriter().setString("nfc",
                        request)));
            }
        }
    }

    NFCLauncher(Container pane) {
        // Start by initializing the HTTP server
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(HTTP_PORT), 0);
            server.createContext("/", new RequestHandler());
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            logger.info("Server is listening on port " + HTTP_PORT);
        } catch (Exception e) {
            NFCLauncher.logger.log(Level.SEVERE, "HTTP Server error", e);
            System.exit(3);
        }

        // Then initialize the GUI
        int fontSize = Toolkit.getDefaultToolkit().getScreenResolution() / 7;
        JLabel msgLabel = new JLabel("Messages:");
        Font font = msgLabel.getFont();
        boolean macOS = System.getProperty("os.name").toLowerCase().contains("mac");
        if (font.getSize() > fontSize || macOS) {
            fontSize = font.getSize();
        }
        int stdInset = fontSize/3;
        msgLabel.setFont(new Font(font.getFontName(), font.getStyle(), fontSize));
        pane.setLayout(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.weightx = 0.0;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.NONE;
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.insets = new Insets(stdInset, stdInset, stdInset, stdInset);
        pane.add(msgLabel, c);

        textArea = new JTextArea();
        textArea.setRows(20);
        textArea.setFont(new Font("Courier", Font.PLAIN, fontSize));
        textArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(textArea);
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1.0;
        c.weighty = 1.0;
        c.gridwidth = 2;
        c.gridy = 1;
        c.insets = new Insets(0, stdInset, 0, stdInset);
        pane.add(scrollPane , c);

        JButton sendBut = new JButton("\u00a0\u00a0\u00a0Send\u00a0\u00a0\u00a0");
        sendBut.setFont(new Font(font.getFontName(), font.getStyle(), fontSize));
        sendBut.addActionListener(new ActionListener() {
            boolean init = true;
            @Override
            public void actionPerformed(ActionEvent event) {
                try {
                    if (init) {
                        init = false;
                        stdout.writeJSONObject(new JSONObjectWriter().setBoolean("@connect@", true));
                    }
                    update(stdout.writeJSONObject(new JSONObjectWriter().setString("native",
                                                                                   sendText.getText())));
                } catch (IOException e) {
                    NFCLauncher.logger.log(Level.SEVERE, "Writing", e);
                    System.exit(3);
                }
            }
        });
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0.0;
        c.weighty = 0.0;
        c.gridwidth = 1;
        c.gridy = 2;
        c.insets = new Insets(stdInset, stdInset, stdInset, 0);
        pane.add(sendBut, c);

        sendText = new JTextField(50);
        sendText.setFont(new Font("Courier", Font.PLAIN, fontSize));
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 1;
        c.insets = new Insets(stdInset, stdInset, stdInset, stdInset);
        pane.add(sendText, c);
    }

    void update(String text) {
        String localTime = ISODateTime.formatDateTime(new Date(), false);
        textArea.setText(localTime.substring(0, 10) + " " + localTime.substring(11, 19) +
            " " + text + "\n" + textArea.getText());
        NFCLauncher.logger.info(text);
    }

    @Override
    public void run() {
        while (true) {
            try {
                stdin.readJSONObject();  // Just syntax checking used in our crude sample
                update(stdin.getJSONString());
            } catch (IOException e) {
                NFCLauncher.logger.log(Level.SEVERE, "Reading", e);
                System.exit(3);
            }
        }
    }

    static BufferedImage getIcon(String name) throws IOException {
        return ImageIO.read(NFCLauncher.class.getResourceAsStream (name));
    }

    public static void main(String[] args) {
        Vector<BufferedImage> icons = new Vector<BufferedImage>();
        try {
            FileHandler fh = new FileHandler(args[0] + File.separator + "logs" + File.separator + "nfc-launcher.log");
            logger.addHandler(fh);
            SimpleFormatter formatter = new SimpleFormatter();
            fh.setFormatter(formatter);
            icons.add(getIcon("nfc32.png"));
            icons.add(getIcon("nfc64.png"));
        } catch (Exception e) {
            System.exit(3);
        }
        for (int i = 0; i < args.length; i++) {
            logger.info("ARG[" + i + "]=" + args[i]);
        }
        JFrame frame = new JFrame("W2NB - Sample #1 [" + args[2] + "]");
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
