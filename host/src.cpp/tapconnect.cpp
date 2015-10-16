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

///////////////////////////////////////////////////////////////////////////////////////////
//                                   tapconnect.cpp                                      //
//                                                                                       //
// This program emulates the proxy required on the host side by the navigator.tapConnect //
// API through the use if Chrome Native Messaging.                                       //
//                                                                                       //
// Note: The security features provided by the navigator.tapConnect are NOT supported!   //
//                                                                                       //
// Author: Anders Rundgren                                                               //
///////////////////////////////////////////////////////////////////////////////////////////

#ifdef WIN32
#define FILE_SEPARATOR   '\\'
#define _CRT_SECURE_NO_WARNINGS
#define JAVA_PLAF " -Dswing.defaultlaf=com.sun.java.swing.plaf.windows.WindowsLookAndFeel"
#define JAVA_LOG " \"-Djava.util.logging.SimpleFormatter.format=%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS %4$s %2$s %5$s%6$s%n\""
#else
#define FILE_SEPARATOR   '/'
#define JAVA_LOG " \"-Djava.util.logging.SimpleFormatter.format=%1\\$tY-%1\\$tm-%1\\$td %1\\$tH:%1\\$tM:%1\\$tS %4\\$s %2\\$s %5\\$s%6\\$s%n\""
#define JAVA_PLAF ""
#endif

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define PROXY_VERSION "1.00"

static char *res;

// "Budget" JSON parser
static char* getJSONProperty (char *name) {
    char *start = strstr(res, name);
    if (!start)    {
        exit(EXIT_FAILURE);
    }
    start += strlen(name);
    if (*start++ != '"') {
        exit(EXIT_FAILURE);
    }
    char *end = strstr(start, "\"");
    if (!end) {
        exit(EXIT_FAILURE);
    }
    int length = end - start + 1;
    char *property = new char[length--];
    strncpy(property, start, length);
    property[length] = 0;
    return property;
}

int main(int argc, char *argv[]) {
    // Reading an initial JSON message which can be of two kinds:
    // 1. Proxy verification which consists of an object {"proxyVersion":"n.nn"}
    // 2. Java application call which consists of an object ("application":"dotted-path",
    //                                                       "url":"invocation-url",
    //                                                       "argumentsB64":"base64url-encoded-json-object"}

    // Chrome presumes message length in native order. Not very cool.
    // The following code therefore only runs on little-endian CPUs.
    int length = 0;
    for (int i = 0; i < 32; i += 8)    {
        length += getchar() << i;
    }

    // We expect a tiny JSON object
    if (length > 10000) {
        exit(EXIT_FAILURE);
    }

    // OK. Read the JSON string
    res = new char[length + 1];
    for (int n = 0; n < length; n++) {
        res[n] = getchar();
    }
    res[length] = 0;

    // Are we doing proxy verification?
    if (strstr(res, "\"proxyVersion\":")) {
        if (strcmp(PROXY_VERSION, getJSONProperty("\"proxyVersion\":"))) {
            exit(EXIT_FAILURE);
        }
        char zeroObject[] = { 2,0,0,0,'{','}' };
        for (int n = 0; n < sizeof(zeroObject); n++) {
            putchar(zeroObject[n]);
        }
        fclose(stdout);
        exit(EXIT_SUCCESS);
    }

    // No, we are executing a Java target application
    char cmd[2000] = "java" JAVA_LOG JAVA_PLAF " -jar ";
    char path[500] = "\"";
    strcat(path, argv[0]);
    int i = strlen (path);
    while (path[--i] != FILE_SEPARATOR)
      ;
    path[i] = 0;
    char fs[] = {FILE_SEPARATOR,0};

    char *application = getJSONProperty("\"application\":");

    // Check that the caller isn't trying to get outside the sandbox
    i = 0;
    char c;
    while(c = application[i++]) {
        if ((c > 'Z' || c < 'A') && (c > 'z' || c < 'a') && (c > '9' || c < '0') && c != '.' && c != '_') {
            exit(EXIT_FAILURE);
        }
    }

    strcat(cmd, path);
    strcat(cmd, fs);
    strcat(cmd, "nfc-launcher.jar\" ");

    // Parameters to called Java program

    // args[0] => Full path to proxy/install
    strcat(cmd, path);
    strcat(cmd, "\" ");

    // args[1] => Dotted path (=application name)
    strcat(cmd, application);
    strcat(cmd, " \"");

    // args[2] => invoking URL
    strcat(cmd, getJSONProperty("\"url\":"));
    strcat(cmd, "\" ");

    // args[3] => Optional arguments to navigator.tapConnect
    strcat(cmd, getJSONProperty("\"argumentsB64\":"));
    
    // args[4..n] => Chrome standard arguments
    for (int i = 1; i < argc; i++) {
        strcat(cmd," ");
        strcat(cmd, argv[i]);
    }
    char fileName[500];
    strcpy(fileName, path + 1);
    strcat(fileName, fs);
    strcat(fileName, "logs");
    strcat(fileName, fs);
    strcat(fileName, "tapconnect-init.log");
    FILE* logFile = fopen(fileName, "w");
    fprintf(logFile, "commmand: %s\n", cmd);

    // This is not the recommended solution for POSIX-compliant systems but hey, this is a PoC...
    int returnCode = system(cmd);
    if (returnCode)    {
        fprintf(logFile, "Error: '%s' %d\n", strerror(returnCode), returnCode);
    }
    fclose(logFile);
    return 0;
}
