(ns zelector.popup.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.ext.extension :refer-macros [get-url]]
            [chromex.ext.tabs :as tabs]
            [cljs.core.async :refer [<! chan]]
            [goog.object :as gobj]
            [goog.dom :as gdom]
            [goog.events :as gvnt]
            [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [zelector.common.util :as util]))

; --- support ---

(defn- close-popup! []
  (.close js/window))

; --- behavior ---
(defn- open-workspace! []
  (let [workspace-url (get-url "workspace.html")
        query-channel (tabs/query (clj->js {:url workspace-url}))]
    ; note: best effort; we don't handle callback errors
    (go (let [found-tabs (first (<! query-channel))]
          (if (empty? found-tabs)
            (tabs/create (clj->js {:url workspace-url}))
            (tabs/update (gobj/get (util/any found-tabs) "id") #js {:active true}))))))

; --- state ---
(defonce state (atom {:enabled true}))

(defmulti read om/dispatch)
(defmulti mutate om/dispatch)

(defmethod read :default
  [{:keys [state] :as env} key params]
  (let [st @state]
    {:value (key st)}))

(defmethod mutate 'toggle-enabled
  [{:keys [state]} _ _]
  {:action
   (fn []
     (swap! state update :enabled not))})

; --- ui ---
(defui Popup
  static om/IQuery
  (query [this] '[:enabled])
  Object
  (render [this]
    (let [{:keys [enabled]} (om/props this)
          toggle-enabled! #(om/transact! this '[(toggle-enabled)])]
      (dom/div
        #js {}
        (dom/h2 #js {} "Zelector")
        (dom/ul #js {}
                (if enabled
                  (dom/li #js {:onClick toggle-enabled!} (dom/span #js {:className "fa fa-check"}) "Enabled")
                  (dom/li #js {:onClick toggle-enabled!} (dom/span #js {:className "fa fa-times"}) "Disabled"))
                (dom/li #js {:onClick #(do (open-workspace!) (close-popup!))}
                        (dom/span #js {:className "fa fa-table"}) "Go to Workspace")
                (dom/li #js {} (dom/span #js {:className "fa fa-wrench"}) "Options"))))))

(def reconciler
  (om/reconciler
    {:state  state
     :parser (om/parser {:read read :mutate mutate})}))

; --- todo ---

(defn- listen*! [class evt-type callback]
  "Given element class, event type and callback, add click event."
  (-> class gdom/getElementByClass (gvnt/listen evt-type callback)))

(defn- unlisten*! [class evt-type]
  (-> class gdom/getElementByClass (gvnt/removeAll evt-type)))

(defn- add-click-listeners! []
  (listen*! "li-enbl" "click" #(log "clicked enable"))
  (listen*! "li-wksp" "click" #(open-workspace!))
  (listen*! "li-opts" "click" #(log "clicked opts"))
  (doseq [class ["li-wksp"]]
    (listen*! class "click" #(close-popup!))))

(defn- remove-click-listeners! []
  (doseq [class ["li-enbl" "li-wksp" "li-opts"]]
    (unlisten*! class "click")))

; --- lifecycle ---

(defn fig-reload-hook []
  (log "fig-reload-hook")
  (remove-click-listeners!)
  (add-click-listeners!))

(defn init! []
  (log "POPUP: init")
  (om/add-root! reconciler Popup (gdom/getElement "app")))
