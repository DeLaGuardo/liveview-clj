(ns liveview.core
  (:refer-clojure :exclude [send])
  (:require [jsonista.core :as json]
            [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.tools.logging :as logger]
            [hiccup2.core :as hiccup])
  (:import [java.util Timer TimerTask]))

(defn start [opts]
  {:timer (Timer.)
   :opts opts
   :instances (atom {})})

(defn stop [{:keys [timer instances]}]
  (doseq [i (vals @instances)]
    ((:stop i)))
  (.cancel timer))

(defn new-id [] (str (java.util.UUID/randomUUID)))

(def ^:dynamic *id*)

(defn js-runtime []
  (slurp (io/resource "liveview/runtime.js")))

(defn morphdom-runtime []
  (slurp (io/resource "liveview/morphdom.js")))

(defn js-init [id {:keys [ws-url ws-path]}]
  (if ws-url
    (str "var LV = LiveView(\"" ws-url "?" id "\")")
    ;; FIXME schema
    (str "var LV = LiveView(\"ws://\" + location.host + \"" ws-path "?" id "\")")))

(defn inject [liveview]
  (list [:script {:src "https://cdn.jsdelivr.net/npm/morphdom@2.3.3/dist/morphdom-umd.min.js"}]
        [:script (hiccup/raw (js-runtime))]
        [:script (hiccup/raw (js-init *id*
                                      (:opts liveview)))]))

(defn expire-task [clb]
  (proxy [TimerTask] []
    (run [] (clb))))

(defn register-instance [{:keys [instances _timer]}
                         {:keys [id] :as instance}]
  (swap! instances assoc id instance))

(defn deregister-instance [{:keys [instances]} id]
  (swap! instances dissoc id))

(defn set-expire-task [{:keys [timer]} clb timeout]
  (let [task (expire-task clb)]
    (.schedule timer task timeout)
    task))

(defprotocol LiveviewPage
  (body [this])
  (send [this topic data]))

(defn start-instance [liveview
                      {:keys [render on-event on-send-encoder on-mount on-disconnect mount-timeout]
                       external-state :state
                       :or {on-event (fn [_ _]
                                       (logger/warn "Undefined event handler"))
                            ;; Delegate data encoding to json/generate-string by default
                            on-send-encoder identity
                            external-state (atom nil)
                            mount-timeout 5000}}]
  (let [id (new-id)
        prev-external-state (atom @external-state)
        rerender (fn [sink state']
                   (when (not= @prev-external-state state')
                     (reset! prev-external-state state')
                     (async/>!! sink (json/write-value-as-string
                                      {:type "rerender"
                                       :value (binding [*id* id]
                                                (render state'))}
                                      json/keyword-keys-object-mapper))))
        state (atom nil)
        instance {:id id
                  :on-mount (fn [socket] ((:on-mount @state) socket))
                  :stop (fn [] ((:stop @state)))}]
    (register-instance liveview instance)
    (letfn [(initialized []
              (let [expire-task (set-expire-task liveview disconnected mount-timeout)]
                {:name :initialized
                 :on-mount (fn [socket]
                             (.cancel expire-task)
                             (reset! state (mounted socket)))
                 :stop (fn [] (.cancel expire-task))}))
            (mounted [{:keys [sink source] :as socket} & send-queue]
              (logger/debug "Mounted" {:instance id})
              (when on-mount (on-mount socket))
              (rerender sink @external-state)
              (add-watch external-state [::watcher id]
                         (fn [_ _ _ state']
                           (rerender sink state')))
              (let [worker
                    (async/go-loop []
                      (if-let [v (async/<! source)]
                        (do
                          (try
                            (let [v' (json/read-value v json/keyword-keys-object-mapper)]
                              (logger/debug "Event" {:instance id
                                                     :event v'})
                              (case (:type v')
                                "event" (on-event external-state
                                                  (:event v') (:payload v'))
                                (logger/warn "Unknown event type" {:instance id
                                                                   :event v'})))
                            (catch Exception e
                              (logger/error e "Error during event handling" {:instance id
                                                                             :event v})))
                          (recur))
                        (do
                          (remove-watch external-state [::watcher id])
                          (reset! state (dismounted)))))]
                (letfn [(on-send [topic data]
                          (async/>!! sink (json/write-value-as-string
                                           {:topic topic
                                            :value (on-send-encoder data)}
                                           json/keyword-keys-object-mapper)))]
                  (when (not-empty send-queue)
                    (doseq [[topic data] send-queue]
                      (on-send topic data)))
                  {:name :mounted
                   :on-mount (fn [_] (throw (Exception. "Double mount. (Race condition?)")))
                   :on-send on-send
                   :stop (fn []
                           (remove-watch external-state [::watcher id])
                           (async/close! sink)
                           (async/close! source)
                           (async/<!! worker))})))
            (dismounted []
              (logger/debug "Dismounted" {:instance id})
              (let [expire-task (set-expire-task liveview disconnected mount-timeout)
                    send-queue (atom [])]
                {:name :dismounted
                 :on-mount (fn [socket]
                             (.cancel expire-task)
                             (reset! state (apply (partial mounted socket) @send-queue)))
                 :on-send (fn [topic data]
                            (swap! send-queue conj [topic data]))
                 :stop (fn [] (.cancel expire-task))}))
            (disconnected []
              (logger/debug "Disconnected" {:instance id})
              (when on-disconnect (on-disconnect))
              (deregister-instance liveview id))]
      (reset! state (initialized)))
    (reify
      LiveviewPage
      (body [this]
        (binding [*id* id]
          (render @external-state)))
      (send [this topic data]
        ((:on-send @state) topic data)))))

(defn render [dom]
  (str (hiccup/html dom)))

(defn page [liveview _req & {:as opts}]
  (start-instance liveview opts))

(defn ws-handler [liveview adapter]
  (fn [req]
    (let [socket (adapter req {})
          id (:query-string req)]
      (if-let [instance (get @(:instances liveview) id)]
        ((:on-mount instance) socket)
        (do (logger/warn "Unknown instance" {:id id})
            (async/close! (:sink socket))) )
      nil)))
