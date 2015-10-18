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

public interface TapConnectProperties {

    String JSON_CONTENT_TYPE     = "application/json";
    
    int HTTP_PORT                = 8099;
    
    int STANDARD_TIMEOUT         = 5000;
    int BACK_CHANNEL_TIMEOUT     = 100000;
    
    String CONTROL_JSON          = "@ctr.l@";
    String APPLICATION_JSON      = "application";
    String OPTIONAL_DATA_JSON    = "optionalData";
    String INVOCATION_URL_JSON   = "url";
    String MESSAGE_JSON          = "message";
    String NOTHING_JSON          = "@N/A@";
}
