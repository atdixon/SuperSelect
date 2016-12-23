(ns zelector.content-script.ui
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [chromex.logging :refer-macros [log info warn error group group-end]]
            [cljs.core.async :refer [<! >! put! chan]]
            [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [goog.dom :as gdom]
            [cljsjs.jquery]
            [jayq.core :as j]
            [zelector.common.core :as zcore]
            [zelector.common.util :as util]
            [zelector.common.trav :as trav]
            [zelector.common.bgx :as bgx]
            [zelector.content-script.parser :as parser]
            [zelector.content-script.buffer-ui :as buffer-ui]
            [zelector.content-script.debug-ui :as debug-ui]))

(defn- save-buffer! [buffer]
  (let [as-record (map :content buffer)]
    (bgx/post-message! {:action "record" :params {:record as-record}})))

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

; --- ui ---
(def debug-ui (om/factory debug-ui/DebugView))
(def buffer-ui (om/factory buffer-ui/BufferView))

(defn- glass-el
  "Render glass covering everything."
  [this & {:keys [mark over combined frozen]}]
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
                      (om/transact! this `[(z/put {:mark/mark ~over})])))}))

(defn mark-els
  "Render 'mark'. "
  [this & {:keys [mark]}]
  (if mark
    (map-indexed
      #(let [border-width 0]
        (dom/div
          #js {:key %1
               :className "zelector-selection-mark-rect"
               :style #js {:borderWidth border-width
                           :top (- (.-top %2) border-width)
                           :left (- (.-left %2) border-width)
                           :width (inc (.-width %2))
                           :height (inc (.-height %2))}}))
      (trav/range->client-rects mark))))

(defn- over-els
  "Render 'over'. "
  [this & {:keys [over]}]
  (if over
    (map-indexed
      #(let [border-width 1]
        (dom/div
          #js {:key %1
               :className "zelector-selection-over-rect"
               :style #js {:borderWidth border-width
                           :top (- (.-top %2) border-width)
                           :left (- (.-left %2) border-width)
                           :width (inc (.-width %2))
                           :height (inc (.-height %2))}}))
      (trav/range->client-rects over))))

(defn- combined-bg-els
  "Render div/s as background for text divs."
  [this & {:keys [combined]}]
  (if combined
    (let [base-rects (trav/range->client-rects combined)
          border-width 0]
      (map-indexed
        #(dom/div
          #js {:key %1
               :className "zelector-selection-base-rect"
               :style #js {:borderTopWidth border-width
                           :borderBottomWidth border-width
                           :top (- (.-top %2) border-width)
                           :left (- (.-left %2) border-width)
                           :width (inc (.-width %2))
                           :height (inc (.-height %2))}})
        base-rects))))

(defn- combined-fg-els
  "Render div with styled text for each split (char or word or...)"
  [this & {:keys [combined]}]
  (if combined
    (for [[[sc _] [_ _] :as split] (partition-range* combined)
          :let [parent-node (util/parent-node sc)
                text-styles (util/elem->text-styles parent-node)
                border-width 0]] ; note: enable border-width to see split outlines
      (map-indexed ; note: we generally expect only one rect.
        (fn [i rect]
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
            (trav/range->str split)))
        (trav/range->client-rects split)))))

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
      (if enabled
        (dom/div nil
          (buffer-ui {:buffer buffer :active active :flush-buffer-fn save-buffer!})
          (if active
            (dom/div nil
              (when ^boolean goog.DEBUG
                (debug-ui {:captured/range combined
                           :over over
                           :char ch
                           :frozen frozen
                           :partition-range-fn partition-range*}))
              (combined-bg-els this :combined combined)
              (combined-fg-els this :combined combined)
              (mark-els this :mark mark)
              (over-els this :over over)
              (glass-el this
                :mark mark :over over :combined combined :frozen frozen))))))))

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
