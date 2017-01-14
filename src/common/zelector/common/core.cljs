(ns zelector.common.core
  (:require [oops.core :refer [oget oset! ocall oapply ocall! oapply!
                               oget+ oset!+ ocall+ oapply+ ocall!+ oapply!+]]))

(defonce extension-version
  (oget (ocall chrome.app "getDetails") "version"))

; ... other core constants etc can go here ...
