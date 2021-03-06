(ns zelector.background.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.chrome-event-channel :refer [make-chrome-event-channel]]
            [chromex.protocols :refer [post-message! get-sender clear] :as proto]
            [chromex.ext.runtime :as runtime]
            [chromex.ext.extension :refer-macros [get-url]]
            [chromex.ext.storage :as storage]
            [chromex.ext.browser-action :as action
             :refer-macros [set-icon set-title set-badge-text set-badge-background-color]]
            [chromex.ext.tabs :as tabs]
            [cljs.core.async :refer [<! >! put! chan]]
            [goog.object :as gobj]
            [goog.string :as gstring]
            [goog.string.format]
            [zelector.common.core :as zcore]
            [zelector.common.util :as util]
            [zelector.common.db :as db]))

; --- ga ---
(defn- fire-ga-event!
  ([category action label]
    (fire-ga-event! category action label nil))
  ([category action label value]
  (let [fields-obj (clj->js
                     {:hitType "event"
                      :eventCategory category
                      :eventAction action
                      :eventLabel label
                      :eventValue value})]
    (if (exists? js/ga)
      (js/ga "send" fields-obj)
      (log "ga" fields-obj ">/null")))))
  
; --- *DANGER* ---
(defn- clear-all-durable*!! []
  (clear (storage/get-local))
  (db/delete-db!!))

; --- client mgmt ---
(defonce clients (atom []))

(defn- add-client! [port]
  (swap! clients conj port))

(defn- remove-client! [port]
  (let [remove-item (fn [coll item] (remove #(identical? item %) coll))]
    (swap! clients remove-item port)))

(defn- message-clients*! [msg]
  (doseq [client @clients]
    (post-message! client (clj->js msg))))

; --- local storage ---
(defn- get-stored-keys
  "Get value for a key/s (a vector of keywords), answer
  channel of result."
  [keys]
  (let [storage (storage/get-local)]
    (proto/get storage (clj->js keys))))

(defn- set-stored-keys! [m]
  (let [storage (storage/get-local)]
    (proto/set storage (clj->js m))))

(defn- broadcast-stored-keys!
  "Broadcast clients with all stored keys."
  []
  (go
    (let [[[items] err] (<! (get-stored-keys [:z/enabled :z/active]))]
      (when-not err
        (message-clients*! {:action "config" :params items})))))

; --- actions ---
(defn- refresh-badge! []
  (go
    (let [[[items] err] (<! (get-stored-keys [:z/enabled]))]
      (when-not err
        (let [{:keys [z/enabled]} (util/js->clj* items)]
          (if enabled
            (do
              (db/count-table #(set-badge-text #js {:text (str %)}))
              (set-icon #js {:path "images/z38-active.png"})
              (set-badge-background-color #js {:color "gray"}))
            (do
              (db/count-table #(set-badge-text #js {:text (str %)}))
              (set-icon #js {:path "images/z38.png"})
              (set-badge-background-color #js {:color "gray"}))))))))

(defn- open-workspace! []
  (let [url (get-url "workspace.html")
        res (tabs/query (clj->js {:url url}))]
    (go
      (let [found-tabs (first (<! res))]
        (if (empty? found-tabs)
          (tabs/create (clj->js {:url url}))
          (tabs/update (gobj/get (util/any found-tabs) "id") #js {:active true}))))))

(defn- toggle-enabled! []
  (go
    (let [[[items] err] (<! (get-stored-keys [:z/enabled]))]
      (when-not err
        (let [{:keys [z/enabled]} (util/js->clj* items)]
          ; note: always clear active state when we toggle enabled
          (set-stored-keys! {:z/enabled (not enabled) :z/active false})
          (refresh-badge!))))))

; --- client event loop ---
; NOTE: Our background "api" supports, e.g.:
;
;   {action: "record",
;    params: {record: []}}
;   {action: "config",
;    params: {z/enabled: true,
;             z/active: true}}
;   {action: "refresh",
;    params: {resource: ["badge"]}}
;   {action: "workspace"}
;
; (Empty params for "config" answers all known config properties to
; client.)
;
; And sends/"answers", e.g.:
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
        (log "action" action params)
        (case action
          "record" (do
                     (fire-ga-event! "Buffer" "AddRecord" (str "ver:" zcore/extension-version) (count params))
                     (db/add-record! (:record params) (:provenance params))
                     (message-clients*! {:action "refresh"
                                         :resource ["db"]})
                     (refresh-badge!))
          "config" (if (empty? params)
                     (broadcast-stored-keys!)
                     (do (set-stored-keys! params)
                         (refresh-badge!)))
          "refresh" (doseq [res (:resource params)]
                      (case res
                        "badge" (refresh-badge!)))
          "workspace" (open-workspace!)))
      (recur))
    (remove-client! client)))

(defn handle-client-connection! [client]
  (add-client! client)
  (post-message! client #js {:action "ping"})
  (run-client-loop! client))

; --- main event loop ---
(defn process-event [event-num event]
  (comment
    log (gstring/format "BACKGROUND: got chrome event (%05d)" event-num) event)
  (let [[event-id args] event]
    (case event-id
      ::runtime/on-connect (apply handle-client-connection! args)
      ::storage/on-changed (broadcast-stored-keys!) ; broadcast all, making client's merge! reconcilation more direct for now.
      ; ::action/on-clicked (toggle-enabled!)
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
    (action/tap-all-events ch)
    (run-event-loop! ch)))

; --- init ---
(defn init! []
  (db/init!)
  (boot-event-loop!)
  (refresh-badge!))