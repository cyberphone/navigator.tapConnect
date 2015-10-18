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
        try {
            logger.setUseParentHandlers(false);
            FileHandler fh = new FileHandler(args[0] + File.separator + "logs" + File.separator + "w2nb-caller.log");
            logger.addHandler(fh);
            SimpleFormatter formatter = new SimpleFormatter();
            fh.setFormatter(formatter);
            JSONObjectReader init = post(args[1], new JSONObjectWriter(), STANDARD_TIMEOUT);
            System.out.println(init);
            String application = init.getString(APPLICATION_JSON);
            String invocationUrl = init.getString(INVOCATION_URL_JSON);
            String optionalData = init.getString(OPTIONAL_DATA_JSON);
            init.checkForUnread();
            Vector<String> commands = new Vector<String>();
            commands.add("java");
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
                JSONObjectWriter request = new JSONObjectWriter()
                    .setBoolean(CONTROL_JSON, false);
                request.setObject(MESSAGE_JSON, stdin.readJSONObject());
                post(args[1], request, STANDARD_TIMEOUT);
             }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Big try", e);
            try {
                post(args[1], new JSONObjectWriter(), 5000);
            } catch (IOException e1) {
            }
            System.exit(3);
        }
    }
}
