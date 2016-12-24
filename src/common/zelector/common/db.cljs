(ns zelector.common.db
  (:require [chromex.logging :refer-macros [log info warn error group group-end]]
            [goog.object :as gobj]
            [dexie]
            [zelector.common.util]))

; -- db --
(def db-name "Zelector")
(defonce ^:private db (atom nil))

(defn- create-db! []
  (let [d (js/Dexie. db-name #js {:autoOpen true})]
    (-> d
      (.version 1)
      (.stores #js {:snippets "++id, record, provenance"}))
    (reset! db d)))

(defn- get-table []
  (gobj/get @db "snippets"))

(defn init! []
  (create-db!))

; --- *DANGER* ---
(defn delete-db!! []
  (.delete @db))

; --- write ---
(defn add-record! [coll provenance]
  (if-not (empty? coll)
    (.add (get-table) (clj->js {:record coll :provenance provenance}))))

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
      (cb (gobj/get row "id") (gobj/get row "record") (gobj/get row "provenance")))))