(ns zelector.content-script.parser
  (:require [chromex.logging :refer-macros [log info warn error group group-end]]
            [om.next :as om]))

(defn- vec-remove [coll pos]
  (vec (concat (subvec coll 0 pos) (subvec coll (inc pos)))))

; --- state structure ---
;{:marks {:ch ch :over nil :mark nil}
; :flags {:debugged nil
;         :frozen nil}
; :mark/ch nil
; :mark/over nil
; :mark/mark nil
; :flag/frozen nil
; :durable {:z/enabled false
;           :z/active false}
; :buffer []}

; --- read ---
(defmulti read om/dispatch)

(defmethod read :default
  [{:keys [state query] :as env} key params]
  (let [st @state]
    {:value (get st key)}))

(defmethod read :buffer
  [{:keys [state query] :as env} key params]
  (let [st @state]
    {:value (get st key [])}))

(defmethod read :durable
  [{:keys [state query] :as env} key params]
  (let [st @state]
    {:value (select-keys (get st :durable {}) query)
     :remote true}))

; --- mutate ---
(defmulti mutate om/dispatch)

(defmethod mutate 'z/put
  [{:keys [state]} _ params]
  {:action
   (fn []
     (swap! state merge params))})

(defmethod mutate 'durable/update
  [{:keys [state query] :as env} key params]
  {:action (fn [] ; optimistic (esp. nice for bg-less testing)
             (swap! state update :durable merge params))
   :remote true})

; --- mutate buffer ---
(defmethod mutate 'buffer/push
  [{:keys [state]} _ {:keys [value]}]
  {:action
   (fn []
     (swap! state update :buffer conj {:id (random-uuid) :content value}))})

(defmethod mutate 'buffer/remove
  [{:keys [state]} _ {:keys [index]}]
  {:action
   (fn []
     (swap! state update :buffer vec-remove index))})

(defmethod mutate 'buffer/clear
  [{:keys [state]} _ _]
  {:action
   (fn []
     (swap! state assoc :buffer (vector)))})
