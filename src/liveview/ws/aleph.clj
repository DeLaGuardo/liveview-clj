(ns liveview.ws.aleph
  (:require [manifold.stream :as stream]
            [aleph.http :as http]
            [clojure.core.async :as async]))

(defn adapter [req {:keys [sink-buf-or-n source-buf-or-n]}]
  (let [socket @(http/websocket-connection req)
        sink (async/chan sink-buf-or-n)
        source (async/chan source-buf-or-n)]
    (stream/connect socket source)
    (stream/connect sink socket)
    {:sink sink
     :source source}))
