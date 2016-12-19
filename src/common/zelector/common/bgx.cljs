(ns zelector.common.bgx
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.protocols :as proto]
            [chromex.ext.runtime :as runtime :refer-macros [connect]]
            [cljs.core.async :refer [<!]]
            [zelector.common.util]))

(defonce port (atom nil))

(defn connect! []
  (reset! port (runtime/connect)))

(defn post-message! [msg]
  (proto/post-message! @port (clj->js msg)))
