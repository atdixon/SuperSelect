(ns zelector.sandbox
  (:require
    [zelector.content-script.ux :as ux]
    [chromex.logging :refer-macros [log info warn error group group-end]]))

(defn fig-reload-hook []
  (log "fig-reload-hook")
  (ux/destroy-basic!)
  (ux/init-basic!))

(defonce setup
  (do
    (log "initial setup")
    (ux/init-basic!)))
