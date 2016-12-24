(ns zelector.workspace.core
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.protocols :refer [post-message!]]
            [chromex.ext.runtime :refer-macros [connect]]
            [cljs.core.async :refer [<!]]
            [goog.object :as gobj]
            [goog.dom :as gdom]
            [cljsjs.jquery]
            [jayq.core :as j]
            [oops.core :refer [oget oset! ocall oapply ocall! oapply!
                               oget+ oset!+ ocall+ oapply+ ocall!+ oapply!+]]
            [cljsjs.papaparse]
            [handsontable]
            [zelector.common.util :as util]
            [zelector.common.db :as db]
            [zelector.common.bgx :as bgx]))

; --- workspace ---
(defonce hot (atom nil))

(defn- create-table []
  (js/Handsontable.
    (gdom/getElement "table")
    (clj->js {; ht bug/oddness: ht requires at min one data row
              :data [[""]]
              :minRows 0
              :rowHeaders true
              :colHeaders false
              :stretchH "all"
              :colWidths 100
              :wordWrap false
              :fillHandle false
              :manualColumnResize true
              :manualRowResize false
              :contextMenu ["remove_row"]
              :afterChange (fn [changes source]
                             (this-as this
                               (if (#{"edit"} source)
                                 (doseq [[row prop old-val new-val] changes]
                                   (let [cell-meta (ocall this "getCellMeta" row 0)
                                         db-id (gobj/get cell-meta "db-id")]
                                     (db/update-record! db-id (ocall this "getDataAtRow" row)))))))
              :afterRemoveRow (fn [index amount logical-rows source]
                                (this-as this
                                  (if-not (#{"clear-table"} source)
                                    (doseq [row logical-rows]
                                      (let [cell-meta (ocall this "getCellMeta" row 0)]
                                        (if-let [db-id (gobj/get cell-meta "db-id")]
                                          (db/delete-record! db-id)))))))})))

(defn- install-table! []
  (reset! hot (create-table)))

(defn- destroy-table! []
  (ocall @hot "destroy")
  (reset! hot nil))

(defn- reinstall-table! []
  (destroy-table!)
  (install-table!))

; --- table row/data manipulation ---
(defn- add-row! [db-id record]
  (let [h @hot
        empty-first-row (ocall h "isEmptyRow" 0)
        insert-loc (if empty-first-row 0 (ocall h "countRows"))
        input (clj->js [record])]
    (if (and (zero? insert-loc) empty-first-row)
      (ocall h "populateFromArray" 0 0 input nil nil nil "overwrite")
      (do
        (ocall h "alter" "insert_row" insert-loc)
        (ocall h "populateFromArray" insert-loc 0 input)))
    (ocall h "setCellMeta" insert-loc 0 "db-id" db-id)))

(defn- load-table-data! []
  (db/each-record #(add-row! %1 %2)))

; --- export ---
(defn- get-data []
  (let [h @hot]
    (.getData h)))

(defn export-csv []
  (let [csv (.unparse js/Papa (get-data))]
    (.open js/window (str "data:text/csv;charset=utf-8,"
                       (js/encodeURIComponent csv)))))

; --- init ---
(defn- bind-handlers! []
  (.bind (j/$ "#download") "click.zelector" #(export-csv))
  (.bind (j/$ "#clear") "click.zelector"
    #(when (.confirm js/window "Are you sure?")
      (db/clear-db!)
      (reinstall-table!)
      (bgx/post-message! {:action "refresh"
                          :params {:resource ["badge"]}}))))

(defn- unbind-handlers! []
  (.unbind (j/$ "#download") "click.zelector")
  (.unbind (j/$ "#clear") "click.zelector"))

; --- background ---
; handle messages like, e.g.,
;   {action: "refresh",
;    params: {resource: ["db"]}}
(defn- handle-message! [msg]
  (let [{:keys [action params]} (util/js->clj* msg)]
    (case action
      "refresh" (do (reinstall-table!)
                    (load-table-data!)) ; poorman's, for now.
      nil)))

(defn- backgound-connect! []
  (let [port (bgx/connect!)]
    (go-loop []
      (when-let [msg (<! port)]
        (handle-message! msg)
        (recur)))))

; --- lifecycle ---
(defn fig-reload-hook []
  (log "fig-reload-hook")
  (unbind-handlers!)
  (reinstall-table!)
  (load-table-data!)
  (bind-handlers!))

(defn init! []
  (log "workspace: init")
  (install-table!)
  (load-table-data!)
  (bind-handlers!)
  (backgound-connect!))