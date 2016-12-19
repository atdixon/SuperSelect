(ns zelector.content-script.bgx
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [cljs.core.async :refer [<!]]
            [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.protocols :refer [post-message!]]
            [chromex.ext.runtime :as runtime :refer-macros [connect]]))

(defonce bg-port (atom nil))

; -- message loop --
(defn process-message! [message]
  (log "CONTENT SCRIPT: got message:" message))

(declare connect-to-background-page!)

(defn run-message-loop! []
  (go-loop []
    (when-let [message (<! @bg-port)]
      (process-message! message)
      (recur))))

; -- more --
(defn connect-to-background-page! []
  (reset! bg-port (runtime/connect))
  (run-message-loop!))

; -- api --
(defn post-record! [record]
  (post-message! @bg-port (clj->js {:record record})))

; -- init --
(defn init! []
  (connect-to-background-page!))