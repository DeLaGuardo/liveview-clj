(ns example.core
  (:require [aleph.http :as http]
            [ataraxy.core :as ataraxy]
            [hiccup.core :as hiccup]
            [integrant.core :as ig]
            [liveview.core :as liveview]
            [liveview.ws.aleph :as liveview-ws]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.default-charset :refer [wrap-default-charset]]
            [hiccup2.core :as hiccup2]
            [com.reasonr.scriptjure :refer [js]]
            [clojure.tools.logging :as logger]))

(defn increment [data]
  [:div
   [:span data]
   [:button {:onclick ""#_(liveview/msg-clb "inc" [])} "Increment!"]])

(defmethod ig/init-key ::router [_ opts]
  (ataraxy/handler opts))

(defmethod ig/init-key ::server [_ {:keys [handler options]}]
  (http/start-server (-> handler
                         (wrap-resource "/example/public")
                         (wrap-content-type)
                         (wrap-default-charset "UTF-8"))
                     options))

(defmethod ig/halt-key! ::server [_ server]
  (.close server))

(defmethod ig/init-key ::liveview [_ opts]
  (liveview/start opts))

(defmethod ig/init-key ::liveview-ws [_ {:keys [liveview]}]
  (liveview/ws-handler liveview liveview-ws/adapter))

(defmethod ig/halt-key! ::liveview [_ liveview]
  (liveview/stop liveview))

(defmethod ig/init-key ::state [_ init]
  (atom init))

(def config
  {[:example.todo/state ::state] {:todos {}}
   :example.todo/handler {:liveview (ig/ref ::liveview)
                          :state (ig/ref :example.todo/state)}
   ::router {:routes {"/todo" [:todo]
                      "/ws" [:liveview]}
             :handlers {:todo (ig/ref :example.todo/handler)
                        :liveview (ig/ref ::liveview-ws)}}
   ::server {:handler (ig/ref ::router)
             :options {:port 8000}}
   ::liveview {:ws-url "ws://localhost:8000/ws"}
   ::liveview-ws {:liveview (ig/ref ::liveview)}})

(def system (atom nil))

(defn stop-system []
  (ig/halt! @system))

(defn -main [& args]
  (ig/load-namespaces config)
  (reset! system (ig/init config))
  (logger/info "System started")
  (.addShutdownHook (Runtime/getRuntime) (Thread. #'stop-system))
  (.. Thread currentThread join))

(comment
  (require 'integrant.repl)
  (integrant.repl/set-prep! (constantly config))

  (integrant.repl/reset)
  )