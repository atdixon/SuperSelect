(ns zelector.content-script.ux
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [chromex.logging :refer-macros [log info warn error group group-end]]
            [cljs.core.async :refer [<! >! put! chan]]
            [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [goog.object :as gobj]
            [goog.dom :as gdom]
            [cljsjs.jquery]
            [jayq.core :as j]
            [zelector.common.core :as zcore]
            [zelector.common.util :as util]
            [zelector.common.trav :as trav]
            [zelector.common.bgx :as bgx]
            [zelector.content-script.parser :as parser]
            [zelector.content-script.debug-panel :as debug]))

; --- util ---
(defn- css-transition-group [props children]
  (js/React.createElement
    js/React.addons.CSSTransitionGroup
    (clj->js (merge props {:children children}))))

; --- actions ---
(defn- save-buffer! [buffer]
  (let [as-record (map :content buffer)]
    (bgx/post-message! {:action "record" :params {:record as-record}})))

; --- ui functions ---
(defn- single-rect? [range]
  (<= (count (trav/range->client-rects range)) 1))

(def ^:private partition-range*
  "Split range for UX; general goal is to split range so that each result resides in
  single client rect but optimizing toward keeping number of splits small."
  (util/resettable-memoize
    (fn [range]
      {:pre [(vector? range)]}
      ; get rid of empty ranges
      (filter (complement trav/empty-range?)
        ; split each range until none have same parent and then trim each range to exclude spaces at ends
        (mapcat #(-> % trav/trim-range trav/partition-range-by*)
          ; split ranges until none have > 1 client rects
          (trav/partition-range-with* range single-rect?))))))

(defn- combine-ranges* [mark over]
  (trav/grow-ranger (trav/combine-ranges mark over) util/punctuation-char?))

(defn- reset-memoizations!
  "Reset memoization state for fns that are sensitive to window dimensions, etc."
  []
  (trav/partition-range-by* :reset!)
  (trav/partition-range-with* :reset!)
  (partition-range* :reset!))

; --- ui components ---
(defui BufferItem
  Object
  (render [this]
    (let [{:keys [index content]} (om/props this)
          length (count content)]
      (dom/li nil
        (dom/span #js {:className "zelector-buffer-item-index"} index)
        (dom/span #js {:className "zelector-buffer-item-content"}
          (if (< length 50)
            content (str (subs content 0 25)
                      "..." (subs content (- length 25)))))
        (dom/span #js {:className "zelector-buffer-item-delete fa fa-times-circle"
                       :onClick   #(om/transact! this `[(buffer/remove {:index ~index})])})))))
(def buffer-item (om/factory BufferItem {:keyfn :id}))

(defui BufferView
  Object
  (render [this]
    (let [{:keys [buffer active]} (om/props this)
          clear-buffer! #(om/transact! this `[(buffer/clear)])]
      (dom/div
        #js {:id "zelector-buffer"}
        (if-not (empty? buffer)
          (dom/ul #js {:onClick #(.stopPropagation %)
                       :onMouseMove #(.stopPropagation %)}
            (css-transition-group
              {:transitionName "zelector-buffer-item"
               :transitionEnterTimeout 500
               :transitionLeaveTimeout 200}
              (reverse
                (map-indexed
                  #(buffer-item {:index %1 :id (:id %2) :content (:content %2)})
                  buffer)))))
        (dom/div #js {:className "zelector-action-bar"}
          (dom/div #js {:style #js {}}
            (dom/b nil "Zelector") ": "
            (dom/span #js {:style #js {:cursor "pointer"}
                           :onClick #(do
                                      (om/transact! this
                                        `[(durable/update {:z/active ~(not active)})])
                                      (when active
                                        (om/transact! this
                                          '[(z/put {:mark/mark nil})])))}
              (if active "active" "inactive"))
            (dom/span #js {} " (Shift+Z)"))
          (dom/div #js {:style #js {:float "right"}}
            (dom/span #js {:className "zelector-action-link"
                           :title "Clear this buffer"
                           :onClick #(clear-buffer!)} "clear")
            (dom/span #js {:className "zelector-action-link"
                           :title "Save this buffer to the backend"
                           :onClick (fn [e]
                                      (save-buffer! buffer)
                                      (clear-buffer!))} "save")))))))
(def buffer-view (om/factory BufferView))

(def debug-info (om/factory debug/DebugInfo))

(defui Zelector
  static om/IQuery
  (query [this] '[:mark/ch :mark/over :mark/mark :flag/frozen
                  :buffer {:durable [:z/enabled :z/active]}])
  Object
  (componentDidMount [this]
    (letfn [(bind [target jq-event-type handler]
              (.bind (j/$ target) jq-event-type handler))]
      (bind js/window "resize.zelector" #(reset-memoizations!))
      (bind js/window "scroll.zelector" #(.forceUpdate this))
      (bind js/document "keydown.zelector"
            (fn [e]
              (let [{{:keys [:z/active]} :durable} (om/props this)
                    inp? (util/input-node? (.-target e))
                    z-key? (and (= (.toLowerCase (.-key e)) "z") (.-shiftKey e))
                    esc-key? (= (.-keyCode e) 27)
                    clear-mark! #(om/transact! this '[(z/put {:mark/mark nil})])]
                (when (and active esc-key?)
                  (clear-mark!))
                (when (and z-key? (or active (not inp?)))
                  (when active (clear-mark!))
                  (om/transact! this `[(durable/update {:z/active ~(not active)})])))))))
  (componentWillUnmount [this]
    (-> js/window j/$ (.unbind ".zelector"))
    (-> js/document j/$ (.unbind ".zelector")))
  (render [this]
    (let [{:keys [:mark/ch :mark/over :mark/mark :flag/frozen :buffer]} (om/props this)
          {{:keys [:z/enabled :z/active]
            :or {enabled zcore/default-enabled active zcore/default-active}} :durable} (om/props this)
          combined (if (and mark over) (combine-ranges* mark over))]
      (when enabled
        (dom/div
          nil
          (buffer-view {:buffer buffer :active active})
          (when active
            (dom/div
              #js {:id "zelector-glass"
                   :style #js {}
                   :onMouseMove (fn [e]
                                  (if-not frozen
                                    (let [glass (.-currentTarget e)
                                          client-x (.-clientX e)
                                          client-y (.-clientY e)
                                          ;; must set pointer events inline/immediately
                                          ;; how else do we capture caret position in
                                          ;; underlying doc/body. then must set it back.
                                          _ (set! (.-style.pointerEvents glass) "none")
                                          [container index] (util/point->caret-position client-x client-y)
                                          _ (set! (.-style.pointerEvents glass) "auto")]
                                      (when (and container (trav/visible-text-node? container))
                                        (let [text (.-textContent container)
                                              char (get text index)]
                                          (when-not (or (nil? char) (util/whitespace-char? char))
                                            (let [range (trav/position->word-range [container index])]
                                              (om/transact! this
                                                `[(z/put {:mark/ch ~char})
                                                  (z/put {:mark/over ~range})]))))))))
                   :onClick (fn [e]
                              (if mark
                                (om/transact! this `[(buffer/push {:value ~(trav/range->str combined)})
                                                     (z/put {:mark/mark nil})])
                                (om/transact! this `[(z/put {:mark/mark ~over})])))}
              (when combined
                (list
                  ; render full combined range as solid block (background)
                  (map-indexed
                    #(let [border-width 2]
                      (dom/div
                        #js {:key %1
                             :className "zelector-selection-base-rect"
                             :style #js {:borderWidth border-width
                                         :top (- (.-top %2) border-width)
                                         :left (- (.-left %2) border-width)
                                         :width (inc (.-width %2))
                                         :height (inc (.-height %2))}}))
                    (trav/range->client-rects combined))
                  ; render div and styled text for each char (or word or ...)
                  (for [[[sc _] [_ _] :as split] (partition-range* combined)
                        :let [parent-node (util/parent-node sc)
                              text-styles (util/elem->text-styles parent-node)]]
                    ; note: we map here over all client rects but split should have produced only one client rect
                    (map-indexed
                      (fn [i rect]
                        (let [border-width 0] ; note: enable border-width to see range outlines
                          (dom/div
                            #js {:key i
                                 :className "zelector-selection-text-rect"
                                 :style (clj->js
                                          ; a couple of games to play here:
                                          ; - we own "text-overflow" and "white-space" in our css
                                          ;  - we translate "text-align" to "text-align-last" since
                                          ;    we are always guaranteed to be rendering single-line rects
                                          (merge (dissoc text-styles :textOverflow :whiteSpace :textAlign)
                                            {:textAlignLast (:textAlign text-styles)
                                             :borderWidth border-width
                                             :top (- (.-top rect) border-width)
                                             :left (- (.-left rect) border-width)
                                             :width (inc (.-width rect))
                                             :height (inc (.-height rect))}))}
                            (trav/range->str split))))
                      (trav/range->client-rects split)))))
              (when mark
                (map-indexed
                  #(let [border-width 2]
                    (dom/div
                      #js {:key %1
                           :className "zelector-selection-mark-rect"
                           :style #js {:borderWidth border-width
                                       :top (- (.-top %2) border-width)
                                       :left (- (.-left %2) border-width)
                                       :width (inc (.-width %2))
                                       :height (inc (.-height %2))}}))
                  (trav/range->client-rects mark)))
              (when over
                (map-indexed
                  #(let [border-width 2]
                    (dom/div
                      #js {:key %1
                           :className "zelector-selection-over-rect"
                           :style #js {:borderWidth border-width
                                       :top (- (.-top %2) border-width)
                                       :left (- (.-left %2) border-width)
                                       :width (inc (.-width %2))
                                       :height (inc (.-height %2))}}))
                  (trav/range->client-rects over)))
              (comment debug-info {:captured/range combined
                           :over over
                           :char ch
                           :frozen frozen
                           :partition-range-fn partition-range*}))))))))

; --- remote ---
(defn- send*
  "Handle remote/send. Assume singleton vector of read/mutate queries.
  Assume reads are for single/flat props (i.e., a :join with single/flat
  props). Assume mutate is update with single params map."
  [{:keys [durable]} cb]
  (if durable
    (let [ast (-> durable om/query->ast :children first)]
      (case (:type ast)
        :call (bgx/post-message! {:action "config"
                                  :params (:params ast)})
        :join (bgx/post-message! {:action "config"})))))

; --- state ---
(def reconciler
  (om/reconciler
    {:state  (atom {})
     :parser (om/parser {:read parser/read :mutate parser/mutate})
     :send send*
     :remotes [:durable]}))

; --- background ---
; handle messages like, e.g.,
;   {action: "config",
;    params: {z/enabled: true,
;             z/active: true}}
; note: any "config" actions we'll merge the data directly into
;       our application/reconciler :durable state state.
(defn- handle-message! [msg]
  (let [{:keys [action params]} (util/js->clj* msg)]
    (case action
      "config" (om.next/merge! reconciler {:durable params})
      nil)))

(defn- backgound-connect! []
  (let [port (bgx/connect!)]
    (go-loop []
      (when-let [msg (<! port)]
        (handle-message! msg)
        (recur)))))

; --- lifecycle ---
(defn- install-glass-mount! []
  (-> (j/$ "<div id=\"zelector-glass-mount\">")
    (.appendTo "body")
    (aget 0)))

(defn init! []
  (backgound-connect!)
  (om/add-root! reconciler Zelector (install-glass-mount!)))

; --- for sandbox ---
(defn init-basic!
  "Init bg-less, i.e., w/o a browser extension env."
  []
  (bgx/connect-null!)
  (om.next/merge! reconciler {:durable {:z/enabled true :z/active true}})
  (om/add-root! reconciler Zelector (install-glass-mount!)))

(defn destroy-basic! []
  (let [mount (gdom/getElement "zelector-glass-mount")]
    (om/remove-root! reconciler mount)
    (gdom/removeNode mount)))
