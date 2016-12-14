(ns zelector.popup.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require
    [chromex.logging :refer-macros [log info warn error group group-end]]
    [chromex.ext.extension :refer-macros [get-url]]
    [chromex.ext.tabs :as tabs]
    [cljs.core.async :refer [<! chan]]
    [goog.dom :as gdom]
    [goog.object :as gobj]))

(defn- open-workspace! [tab]
  (let [workspace-url (get-url "workspace.html")
        query-channel (tabs/query (clj->js {:url workspace-url}))]
    ; note: best effort; we don't handle callback errors
    (go (let [found-tabs (first (<! query-channel))]
          (if (empty? found-tabs)
            (tabs/create (clj->js {:url workspace-url}))
            (tabs/update (gobj/get (util/any found-tabs) "id") #js {:active true}))))))

; todo -- add event handlers to popup

(defn init! []
  (log "POPUP: init"))
