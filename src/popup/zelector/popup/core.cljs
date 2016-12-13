(ns zelector.popup.core
  (:require
    [chromex.logging :refer-macros [log info warn error group group-end]]
    [chromex.ext.extension :refer-macros [get-url]]
    [goog.dom :as gdom]))

(defn init! []
  (log "POPUP: init")
  (log "DATAWORK URL:" (get-url "workspace.html")))
