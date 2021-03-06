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
package temp1;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

import java.util.Vector;

import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import org.webpki.json.JSONObjectReader;
import org.webpki.json.JSONObjectWriter;
import org.webpki.json.JSONOutputFormats;
import org.webpki.json.JSONParser;

import org.webpki.net.HTTPSWrapper;

// CLI test program for manually activating the target application

import static org.webpki.tapconnect.TapConnectProperties.*;

public class CallingW2NB {
    
    static Logger logger = Logger.getLogger("MyLog");

    static Process process;
    static StdinJSONPipe stdin;
    static StdoutJSONPipe stdout;
    
    static class StdinJSONPipe {
        
        String jsonString;
        
        DataInputStream dis;
        
        StdinJSONPipe() {
            dis = new DataInputStream(process.getInputStream());
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

    static class StdoutJSONPipe {
        
        OutputStream os;
        
        StdoutJSONPipe() {
            os = process.getOutputStream();
        }

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
            os.write(blob);
            os.flush();
            return new String(utf8, "UTF-8");
        }
    }
    
    static JSONObjectReader post(String url, JSONObjectWriter json, int timeout) throws IOException {
        HTTPSWrapper wrap = new HTTPSWrapper();
        wrap.setTimeout(timeout);
        wrap.setHeader("Content-Type", JSON_CONTENT_TYPE);
        wrap.setRequireSuccess(true);
        wrap.makePostRequest(url, json.serializeJSONObject(JSONOutputFormats.NORMALIZED));
        // We expect JSON, yes
        if (!wrap.getRawContentType().equals(JSON_CONTENT_TYPE)) {
            throw new IOException("Content-Type must be \"" + JSON_CONTENT_TYPE + "\" , found: " + wrap.getRawContentType());
        }
        return JSONParser.parse(wrap.getData());        
    }

    public static void main(final String[] args) {
        if (args.length != 2) {
            System.out.println("1: full proxy/install path\n2: URL-to-NFC-service");
            System.exit(3);
        }
        String step = "initial";
        try {
            logger.setUseParentHandlers(false);
            System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS %4$s %2$s %5$s%6$s%n");
            FileHandler fh = new FileHandler(args[0] + File.separator + "logs" + File.separator + "w2nb-caller.log");
            logger.addHandler(fh);
            SimpleFormatter formatter = new SimpleFormatter();
            fh.setFormatter(formatter);
            JSONObjectReader init = post(args[1], new JSONObjectWriter(), STANDARD_TIMEOUT);
            logger.info("Invocation:" + init);
            String application = init.getString(APPLICATION_JSON);
            String invocationUrl = init.getString(INVOCATION_URL_JSON);
            String optionalData = init.getString(OPTIONAL_DATA_JSON);
            init.checkForUnread();
            step = "starting";
            Vector<String> commands = new Vector<String>();
            commands.add("java");
            commands.add("-Djava.util.logging.SimpleFormatter.format=%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS %4$s %2$s %5$s%6$s%n");
            if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
                commands.add("-Dswing.defaultlaf=com.sun.java.swing.plaf.windows.WindowsLookAndFeel");
            }
            commands.add("-jar");
            commands.add(args[0] + File.separator + "apps" + File.separator + application + File.separator + application + ".jar");
            commands.add(args[0]);
            commands.add(application);
            commands.add(invocationUrl);
            commands.add("e30");
            commands.add(optionalData);
            process = Runtime.getRuntime().exec(commands.toArray(new String[0]));
            stdin = new StdinJSONPipe();
            stdout = new StdoutJSONPipe();
            step = "I/O";
 
            new Thread() {
                @Override
                public void run() {
                    while (true) {
                        try {
                            JSONObjectReader response = post(args[1],
                                                             new JSONObjectWriter().setBoolean(CONTROL_JSON, true),
                                                             BACK_CHANNEL_TIMEOUT);
                            if (response.hasProperty(NOTHING_JSON)) {
                                logger.info("Nothing");
                            } else if (response.hasProperty(CLOSE_JSON)) {
                                logger.info("Web-side close");
                                System.exit(3);
                            } else {
                                stdout.writeJSONObject(new JSONObjectWriter(response));
                            }
                        } catch (Exception e) {
                            logger.log(Level.SEVERE, "Background", e);
                            System.exit(3);
                        }
                    }
                }
            }.start();

            while (true) {
                post(args[1],
                     new JSONObjectWriter()
                         .setBoolean(CONTROL_JSON, false)
                         .setObject(MESSAGE_JSON, stdin.readJSONObject()),
                     STANDARD_TIMEOUT);
             }
        } catch (Exception e) {
            logger.log(Level.SEVERE, step, e);
            try {
                // Final effort telling the world that we are closing for today...
                post(args[1], new JSONObjectWriter(), STANDARD_TIMEOUT);
            } catch (IOException e1) {
            }
            System.exit(3);
        }
    }
}
