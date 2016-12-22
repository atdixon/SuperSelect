(ns zelector.content-script.parser
  (:require [chromex.logging :refer-macros [log info warn error group group-end]]
            [om.next :as om]))

(defn- vec-remove [coll pos]
  (vec (concat (subvec coll 0 pos) (subvec coll (inc pos)))))

;{:ch nil
; :over nil
; :mark nil
; :freeze nil
; :active false
; :debug-active true
; :buff []
; :flags {:debugged nil
;         :frozen nil}
; :durable {:z/enabled false
;           :z/active false}
; }

(defmulti read om/dispatch)
(defmulti mutate om/dispatch)

(defmethod read :default
  [{:keys [state] :as env} key params]
  (let [st @state]
    {:value (key st)}))

(defmethod read :durable
  [{:keys [state query] :as env} key params]
  (let [st @state]
    {:value (key st)
     :remote true}))

; todo
(defmethod mutate 'z/set-flag
  [{:keys [state]} _ params]
  {:action
   (fn []
     (swap! state update :flags merge params))})

(defmethod mutate 'z/update-durable
  [{:keys [state query] :as env} key params]
  {:remote true})

; todo plugin enabled (also how do updates to storage get pushed out to content scripts etc)
; todo simplify mutates to 'z/set
(defmethod mutate 'over/set
  [{:keys [state]} _ {:keys [value]}]
  {:action
   (fn []
     (swap! state assoc :over value))})

(defmethod mutate 'mark/set
  [{:keys [state]} _ {:keys [value]}]
  {:action
   (fn []
     (swap! state assoc :mark value))})

(defmethod mutate 'ch/set
  [{:keys [state]} _ {:keys [value]}]
  {:action
   (fn []
     (swap! state assoc :ch value))})

(defmethod mutate 'buffer/push
  [{:keys [state]} _ {:keys [value]}]
  {:action
   (fn []
     (swap! state update :buff conj {:id (random-uuid) :content value}))})

(defmethod mutate 'buffer/remove
  [{:keys [state]} _ {:keys [index]}]
  {:action
   (fn []
     (swap! state update :buff vec-remove index))})

(defmethod mutate 'buffer/clear
  [{:keys [state]} _ _]
  {:action
   (fn []
     (swap! state assoc :buff (vector)))})

(defmethod mutate 'debug/toggle-freeze
  [{:keys [state]} _ _]
  {:action
   (fn []
     (swap! state update :freeze #(if (nil? %) true nil)))})

(defmethod mutate 'active/set
  [{:keys [state]} _ {:keys [value]}]
  {:action
   (fn []
     (swap! state assoc :active value))})

(defmethod mutate 'active/toggle
  [{:keys [state]} _ _]
  {:action
   (fn []
     (swap! state update :active not))})
