(ns zelector.background.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require
    [goog.object :as gobj]
    [goog.string :as gstring]
    [goog.string.format]
    [cljs.core.async :refer [<! chan]]
    [chromex.logging :refer-macros [log info warn error group group-end]]
    [chromex.chrome-event-channel :refer [make-chrome-event-channel]]
    [chromex.protocols :refer [post-message! get-sender]]
    [chromex.ext.runtime :as runtime]
    [chromex.ext.extension :refer-macros [get-url]]
    [zelector.common.util :as util]
    [zelector.common.db :as db]))

(defonce clients (atom []))

; --- client management ---

(defn add-client! [client]
  (log "BACKGROUND: client connected" (get-sender client))
  (swap! clients conj client))

(defn remove-client! [client]
  (log "BACKGROUND: client disconnected" (get-sender client))
  (let [remove-item (fn [coll item] (remove #(identical? item %) coll))]
    (swap! clients remove-item client)))

(defn tell-clients-about-new-data! []
  (doseq [client @clients]
    (post-message! client #js {:data-refresh true})))

; --- client event loop ---

(defn run-client-message-loop! [client]
  (go-loop []
    (when-let [message (<! client)]
      (log "BACKGROUND: got client message:" message "from" (get-sender client))
      (when (object? message)
        (when-let [record (gobj/get message "record")]
          (db/add-record! record)
          (tell-clients-about-new-data!)))
      (recur))
    (remove-client! client)))

; --- event handlers ---

(defn handle-client-connection! [client]
  (add-client! client)
  (post-message! client "hello from BACKGROUND PAGE!")
  (run-client-message-loop! client))

; --- event loop ---

(defn process-chrome-event [event-num event]
  (log (gstring/format "BACKGROUND: got chrome event (%05d)" event-num) event)
  (let [[event-id event-args] event]
    (case event-id
      ::runtime/on-connect (apply handle-client-connection! event-args)
      nil)))

(defn run-chrome-event-loop! [chrome-event-channel]
  (go-loop [event-num 1]
    (when-let [event (<! chrome-event-channel)]
      (process-chrome-event event-num event)
      (recur (inc event-num)))))

(defn boot-chrome-event-loop! []
  (let [chrome-event-channel (make-chrome-event-channel (chan))]
    (runtime/tap-on-connect-events chrome-event-channel)
    (runtime/tap-on-message-events chrome-event-channel)
    (run-chrome-event-loop! chrome-event-channel)))

; --- init ---

(defn init! []
  (log "BACKGROUND: init")
  (boot-chrome-event-loop!))