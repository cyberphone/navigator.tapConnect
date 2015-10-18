package temp1;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Vector;

import org.webpki.json.JSONObjectReader;
import org.webpki.json.JSONObjectWriter;
import org.webpki.json.JSONOutputFormats;
import org.webpki.json.JSONParser;
import org.webpki.net.HTTPSWrapper;

import static org.webpki.tapconnect.TapConnectProperties.*;

public class CallingW2NB {
    
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
    
    synchronized static JSONObjectReader post(String url, JSONObjectWriter json, int timeout) throws IOException {
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

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("1: full proxy/install path\n2: URL-to-NFC-service");
            System.exit(3);
        }
        try {
            JSONObjectReader init = post(args[1], new JSONObjectWriter(), 5000);
            System.out.println(init);
/*
            Vector<String> commands = new Vector<String>();
            commands.add("java");
            commands.add("-jar");
            commands.add(args[0] + File.separator + "apps" + File.separator + args[1] + File.separator + args[1] + ".jar");
            commands.add(args[0]);
            commands.add(args[1]);
            commands.add("http://evil.com/yeah");
            commands.add("e30");
            commands.add("e30");
            process = Runtime.getRuntime().exec(commands.toArray(new String[0]));
            stdin = new StdinJSONPipe();
            stdout = new StdoutJSONPipe();
            while (true) {
                    stdin.readJSONObject();
                    System.out.println(stdin.getJSONString());
            }
*/
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(3);
        }
    }
}
