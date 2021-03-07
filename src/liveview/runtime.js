var LiveView = function(endpoint) {
    var running = true,
        registeredHandlers = {},
        obj = {
            socket: null,
            sendEvent: function(type, event) {
                console.log(event);
                if (this.socket.readyState != 1) {
                    console.log("Can't send an event, socket is not connected");
                } else {
                    this.socket.send(JSON.stringify({type: "event", event: type, payload: event}));
                }
            },
            connect: function() {
                obj.socket = new WebSocket(endpoint);
                obj.socket.onmessage = function(e) {
                    var data = JSON.parse(e.data);
                    if (data.type == "rerender") {
                        morphdom(document.documentElement, data.value);
                    } else if (registeredHandlers[data.topic]) {
                        registeredHandlers[data.topic](data.value);
                    } else {
                        console.error("Unknown topic " + data.topic);
                    }
                };
                obj.socket.onclose = function(e) {
                    if (running) {
                        setTimeout(function() { obj.connect(); }, 1000);
                    }
                };
            },
            stop: function() {
                running = false;
                obj.socket.close();
            },
            regHandler: function(name, handler) {
                registeredHandlers[name] = handler;
            },
            regHandlers: function(handlers, callback) {
                Object.entries(handlers).forEach(function(el) {
                    registeredHandlers[el[0]] = el[1];

                });
                callback.call();
            },
            dropHandler: function(topic) {
                delete(registeredHandlers[topic]);
            }
        };
    obj.connect();
    return obj;
};
