# navigator.tapConnect

This is a highly experimental project.  You better keep out for a while :-)

### API
This API extends the **navigator** object by a *single* method<br>
**tapConnect**('*Name of target application*' [, *optionalArgument*]) which
returns a JavaScript **Promise** to a **port** object.

The **port** object supports the following methods and events:
* **postMessage**(*message*)
* **disconnect**()
* **addMessageListener**(function(*message*))
* **addConnectionListener**(function(*initialized*))

*optionalArgument* and *message* must be a valid JSON-serializable JavaScript objects.

*initialized* is a boolean which is true when a "tap" has been initiated
and false when the connection terminates.

An example which could be hosted in an ordinary (*non-privileged*) web page:
```javascript
navigator.tapConnect('com.example.myapp').then(function(port) {

    port.addMessageListener(function(message) {
        // We got a message from the external application...
    });

    port.addConnectionListener(function(initialized) {
        if (initialized) {
           // Someone tapped!
        }
    });

    port.postMessage({greeting:'External app, how are you doing?'});
    // Note: JavaScript serialization makes the above a genuine JSON object

    port.disonnect();  // Good-bye external application...

}, function(err) {
    console.debug(err);
});
```
The argument to **tapConnect** holds the name of the specifically adapted local application to invoke.   The current scheme uses a Java-inspired dotted path which is supposed to be mapped to the external device's OS in an OS-specific way.
