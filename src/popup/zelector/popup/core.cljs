(ns zelector.popup.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.ext.extension :refer-macros [get-url]]
            [chromex.ext.tabs :as tabs]
            [cljs.core.async :refer [<! >! put! chan]]
            [goog.object :as gobj]
            [goog.dom :as gdom]
            [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [zelector.common.bgx :as bgx]
            [zelector.common.util :as util]))

; --- support ---
(defn- close-popup! []
  (.close js/window))

; --- actions ---
(defn- open-workspace! []
  (let [url (get-url "workspace.html")
        res (tabs/query (clj->js {:url url}))]
    (go
      (let [found-tabs (first (<! res))]
        (if (empty? found-tabs)
          (tabs/create (clj->js {:url url}))
          (tabs/update (gobj/get (util/any found-tabs) "id") #js {:active true}))))))

; --- state ---
;   assume z/enabled true until proven otherwise; om/next
;   behaves better when initial state is present. (i.e.,
;   does not seem to do an *initial* render component
;   appropriately on om/merge)
(defonce state (atom {:z/enabled false}))

(defmulti read om/dispatch)
(defmulti mutate om/dispatch)

(defmethod read :default
  [{:keys [state]} key _]
  (let [st @state]
    (log "reading ->" (get st key))
    {:value (get st key)
     :remote true}))

(defmethod mutate 'z/update
  [env key params]
  {:remote true})

; --- remote ---
(defn- remote
  "Handle remote/send."
  [{:keys [remote]} cb]
  (let [ast (-> remote om/query->ast :children first)]
    (case (:type ast)
      :prop (do
              (log "post:" {:action "config"})
              (bgx/post-message! {:action "config"}))
      :call (when (= (:key ast) 'z/update)
              (log "post:" {:action "config" :params (:params ast)})
              (bgx/post-message! {:action "config"
                                  :params (:params ast)})))))

; --- ui ---
(defui Popup
  static om/IQuery
  (query [this] '[:z/enabled])
  Object
  (render [this]
    (let [{:keys [z/enabled]} (om/props this)]
      (log "rendering" enabled)
      (dom/div
        #js {}
        (dom/h2 #js {} "Zelector")
        (dom/ul #js {}
                (if enabled
                  (dom/li #js {:onClick #(om/transact! this '[(z/update {:z/enabled false})])}
                          (dom/span #js {:className "fa fa-check"}) "Enabled")
                  (dom/li #js {:onClick #(om/transact! this '[(z/update {:z/enabled true})])}
                          (dom/span #js {:className "fa fa-times"}) "Disabled"))
                (dom/li #js {:onClick #(do (open-workspace!) (close-popup!))}
                        (dom/span #js {:className "fa fa-table"}) "Go to Workspace"))))))

(def parser
  (om/parser {:read read :mutate mutate}))

(def reconciler
  (om/reconciler
    {:state  state
     :parser parser
     :send   remote}))

; --- background ---
; handle messages like, e.g.,
;   {action: "config",
;    params: {z/enabled: true}}
; note: any "config" actions we'll merge the data directly into
;       our application/reconciler state.
(defn handle-message! [msg]
  (let [{:keys [action params]} (util/js->clj* msg)]
    (case action
      "config" (om.next/merge! reconciler params)
      nil)))

(defn backgound-connect! []
  (let [port (bgx/connect!)]
    (go-loop []
      (when-let [msg (<! port)]
        (handle-message! msg)
        (recur)))))

; --- lifecycle ---
(defn fig-reload-hook []
  (log "fig-reload-hook")
  (om/remove-root! reconciler (gdom/getElement "app"))
  (om/add-root! reconciler Popup (gdom/getElement "app")))

(defn init! []
  (log "popup: init")
  (backgound-connect!)
  (om/add-root! reconciler Popup (gdom/getElement "app")))
