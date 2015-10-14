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

import java.io.DataInputStream;
import java.io.IOException;

import org.webpki.json.JSONObjectReader;
import org.webpki.json.JSONParser;

public class StdinJSONPipe {
    
    String jsonString;
    
    DataInputStream dis;
    
    public StdinJSONPipe() {
        dis = new DataInputStream(System.in);
    }

    public JSONObjectReader readJSONObject() throws IOException {
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

    public String getJSONString () {
        return jsonString;
    }

}
