(ns example.increment
  (:require [liveview.core :as liveview]
            [integrant.core :as ig]
            [com.reasonr.scriptjure :refer [js]]
            [hiccup2.core :as hiccup]))

(defn render [liveview data]
  [:html
   [:head
    [:title "Increment example"]
    (liveview/inject liveview)]
   [:body
    [:div#number "Number: " data]
    [:div [:button {:onclick (js (LV.sendEvent "increment" {})
                                 (return true))}
           "Increment!"]]
    [:script (hiccup/raw (js (do (var colors ["red" "green" "blue"])
                                 (LV.regHandler "number" (fn [s]
                                                           (var n (document.getElementById "number"))
                                                           (var x (% (+ (% s 3) 3) 3))
                                                           (set! n.innerText (+ "Number: " s))
                                                           (set! n.style.color (aget colors x))
                                                           (return true))))))]]])

(defmethod ig/init-key ::handler [_ {:keys [liveview state]}]
  (fn [req]
    (let [state (or state (atom 0))
          page (liveview/page liveview req
                              :render (fn [_]
                                        (liveview/render (render liveview @state)))
                              :on-event (fn [_ type payload]
                                          (case type
                                            "increment" (swap! state inc))))]
      (add-watch state [::watcher req]
                 (fn [_ _ _ state']
                   (liveview/send page "number" state')))
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (liveview/body page)})))
