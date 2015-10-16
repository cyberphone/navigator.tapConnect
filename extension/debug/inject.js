/*
 *  Copyright 2006-2015 WebPKI.org (http://tapapi.org).
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
 
 "use strict";

console.debug('Injected page loaded');

var _promise_;

var _ports = [];

var org = org || {};
org.tapapi = org.tapapi || {};

org.tapapi.port = function(tabid) {
   this.tabid = tabid;
   _ports[tabid] = this;
};

org.tapapi.port.prototype.addMessageListener = function(callback) {
   this.messageCallback = callback;
};

org.tapapi.port.prototype.addConnectionListener = function(callback) {
   this.connectionCallback = callback;
};

org.tapapi.port.prototype.disconnect = function() {
    var msg = {};
    msg.src = 'webdis_';
    msg.tabid = this.tabid;
    window.postMessage(msg, '*');
};

org.tapapi.port.prototype.postMessage = function(message) {
    var msg = {};
    msg.src = 'webmsg_';
    msg.tabid = this.tabid;
    msg.message = message;
    window.postMessage(msg, '*');
};

// Forward the message from extension.js to inject.js
window.addEventListener("message", function(event) {
    // We only accept messages from ourselves
    if (event.source !== window || !event.data.src) 
        return;

    // and forward to extension
    if (event.data.src === "openres_") {
        console.debug('got open return');
        if (event.data.res.success) {
            _promise_.resolve(new org.tapapi.port(event.data.res.success));
        } else if (event.data.res.err) {
            _promise_.reject(event.data.res.err);
        } else {
            _promise_.reject("Internal error");
        }
        delete document._promise_;
    } else if (event.data.src === "natmsg_") {
        console.debug('inject req:' + JSON.stringify(event.data.req));
        if (_ports[event.data.req.tabid].messageCallback) {
            _ports[event.data.req.tabid].messageCallback(event.data.req.message);
        } else {
            console.debug('missing listener: ' + JSON.stringify(event.data.req));
        }
    } else if (event.data.src === "natcon_") {
        console.debug('inject connect:' + JSON.stringify(event.data.req));
        if (_ports[event.data.req.tabid].connectionCallback) {
            _ports[event.data.req.tabid].connectionCallback(event.data.req.connect);
        } else {
            console.debug('missing listener: ' + JSON.stringify(event.data.req));
        }
    } else if (event.data.src !== "webdis_") {
        console.debug('page-other: ' + event.data.src);
    }
});

navigator.tapConnect = function(applicationName, optionalArguments) {
    return new Promise(function(resolve, reject) {
        var msg = {};
        msg.src = 'openreq_';
        msg.origin = location.href;
        msg.application = applicationName;
        msg.arguments = optionalArguments ? optionalArguments : {};
        window.postMessage(msg, '*');
        _promise_ = {resolve: resolve, reject: reject};
    });
};

