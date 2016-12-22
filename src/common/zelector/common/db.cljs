(ns zelector.common.db
  (:require [chromex.logging :refer-macros [log info warn error group group-end]]
            [goog.object :as gobj]
            [dexie]
            [zelector.common.util]))

(defn- create-db! []
  (let [db (js/Dexie. "Zelector" #js {:autoOpen true})]
    (-> db
      (.version 1)
      (.stores #js {:snippets "++id, record"}))
    db))

(defonce db (create-db!))

(defn- get-table []
  (gobj/get db "snippets"))

; --- write ---
(defn add-record! [coll]
  (if-not (empty? coll)
    (.add (get-table) (clj->js {:record coll}))))

(defn update-record! [db-id coll]
  (.update (get-table) db-id (clj->js {:record coll})))

(defn delete-record! [db-id]
  (.delete (get-table) db-id))

(defn clear-db! []
  (.delete (.toCollection (get-table))))

; --- read ---
(defn count-table [cb]
  (.count (get-table) cb))

(defn each-record [cb]
  (.toArray
    (get-table)
    #(doseq [row %]
      (cb (gobj/get row "id") (gobj/get row "record")))))