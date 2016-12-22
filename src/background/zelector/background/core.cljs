(ns zelector.background.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.chrome-event-channel :refer [make-chrome-event-channel]]
            [chromex.protocols :refer [post-message! get-sender] :as proto]
            [chromex.ext.runtime :as runtime]
            [chromex.ext.extension :refer-macros [get-url]]
            [chromex.ext.storage :as storage]
            [cljs.core.async :refer [<! chan]]
            [goog.object :as gobj]
            [goog.string :as gstring]
            [goog.string.format]
            [zelector.common.util :as util]
            [zelector.common.db :as db]))

(defonce clients (atom []))

(defn get-stored-keys
  "Get value for a key/s (a vector of keywords), answer
  channel of result."
  [keys]
  (let [storage (storage/get-local)]
    (proto/get storage (clj->js keys))))

(defn set-stored-keys! [m]
  (let [storage (storage/get-local)]
    (proto/set storage (clj->js m))))

; --- clients ---
(defn add-client! [port]
  (swap! clients conj port))

(defn remove-client! [port]
  (let [remove-item (fn [coll item] (remove #(identical? item %) coll))]
    (swap! clients remove-item port)))

(defn message-clients*! [msg]
  (doseq [client @clients]
    (post-message! client (clj->js msg))))

(defn broadcast-stored-keys!
  "Broadcast clients with all stored keys."
  []
  (go
    (let [[[items] err] (<! (get-stored-keys [:z/enabled :z/active]))]
      (when-not err
        (message-clients*! {:action "config" :params items})))))

; NOTE: Our background "API" supports, e.g.:
;
;   {action: "record",
;    params: {record: []}}
;
;   {action: "config",
;    params: {z/enabled: true,
;             z/active: true}}
;
; (Empty params for "config" answers all known config properties to
; client.)
;
; And answers, e.g.:
;
;   {action: "refresh",
;    params: {resource: ["db"]}}
;   {action: "config",
;    params: {z/enabled: true}}
;   {action: "ping"}
;
(defn run-client-loop! [client]
  (go-loop []
    (when-let [msg (<! client)]
      (let [{:keys [action params]} (util/js->clj* msg)]
        (case action
          "record" (do
                     (db/add-record! (:record params))
                     (message-clients*! {:action "refresh"
                                         :resource ["db"]}))
          "config" (if (empty? params)
                     (broadcast-stored-keys!)
                     (set-stored-keys! params))))
      (recur))
    (remove-client! client)))

(defn handle-client-connection! [client]
  (add-client! client)
  (post-message! client #js {:action "ping"})
  (run-client-loop! client))

; --- event loop ---
(defn process-event [event-num event]
  (let [[event-id args] event]
    (case event-id
      ::runtime/on-connect (apply handle-client-connection! args)
      ::storage/on-changed (broadcast-stored-keys!) ; broadcast all, making client's merge! reconcilation more direct for now.
      nil)))

(defn run-event-loop! [ch]
  (go-loop [event-num 1]
    (when-let [event (<! ch)]
      (process-event event-num event)
      (recur (inc event-num)))))

(defn boot-event-loop! []
  (let [ch (make-chrome-event-channel (chan))]
    (runtime/tap-on-connect-events ch)
    (runtime/tap-on-message-events ch)
    (storage/tap-on-changed-events ch)
    (run-event-loop! ch)))

; --- init ---
(defn init! []
  (boot-event-loop!))