(ns zelector.sandbox
  (:require
    [zelector.content-script.ux :as ux]
    [chromex.logging :refer-macros [log info warn error group group-end]]))

(log "sandbox time")

(ux/init-basic!)
