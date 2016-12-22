(ns zelector.common.bgx
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.protocols :as proto]
            [chromex.ext.runtime :as runtime :refer-macros [connect]]
            [cljs.core.async :refer [<!]]
            [zelector.common.util]))

(defonce ^:private port (atom nil))

(defn connect! []
  (reset! port (runtime/connect)))

(defn connect-null! []
  (reset! port (reify proto/IChromePort
                 (post-message! [this message]
                   (log (print-str message) ">/null")))))

(defn post-message! [msg]
  (proto/post-message! @port (clj->js msg)))
