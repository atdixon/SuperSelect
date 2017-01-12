(ns zelector.demo
  (:require
    [zelector.content-script.ui :as ui]
    [chromex.logging :refer-macros [log info warn error group group-end]]))

(defn fig-reload-hook []
  (log "fig-reload-hook")
  (ui/destroy-basic!)
  (ui/init-basic!))

(defonce setup
  (ui/init-basic!))
