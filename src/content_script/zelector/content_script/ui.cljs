(ns zelector.content-script.ui
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [chromex.logging :refer-macros [log info warn error group group-end]]
            [chromex.ext.extension :refer-macros [get-url]]
            [clojure.data :as data]
            [cljs.core.async :refer [<! >! put! chan]]
            [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [goog.dom :as gdom]
            [goog.string :as gstr]
            [goog.string.format]
            [cljsjs.jquery]
            [jayq.core :as j]
            [zelector.common.util :as util]
            [zelector.common.trav :as trav]
            [zelector.common.bgx :as bgx]
            [zelector.content-script.parser :as parser]
            [zelector.content-script.buffer-ui :as buffer-ui]
            [zelector.content-script.debug-ui :as debug-ui]))

(defn- save-buffer! [buffer]
  (let [as-record (map :content buffer)
        provenance (.-location.href js/window)]
    (bgx/post-message! {:action "record" :params {:record as-record :provenance provenance}})))

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

(defn- combine-ranges*
  "Strategy for combining mark...over, including heuristics to incorporate or
  exclude punctuation at ends of combination etc."
  [mark over]
  (if (= mark over)
    mark
    (let [combined (trav/combine-ranges mark over)
          grown-r (trav/grow-ranger combined #(or (util/quotey-char? %) (util/punctuation-char? %)))]
      (if (not= combined grown-r)
        (trav/grow-rangel grown-r util/quotey-char?) grown-r))))

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
  "Render 'mark'."
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
  "Render 'over'."
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
          (let [{:keys [:mark :buffer]} (om/props this)
                {{:keys [:z/active]} :durable} (om/props this)
                inp? (util/input-node? (.-target e))
                z-key? (= (.toLowerCase (.-key e)) "z")
                s-key? (= (.toLowerCase (.-key e)) "s")
                esc-key? (= (.-keyCode e) 27)]
            (when (and esc-key? (not mark)) ; esc key with no mark deactivates
              (om/transact! this '[(durable/update {:z/active false})]))
            (when esc-key? ; esc key always clears mark
              (om/transact! this '[(z/put {:mark/mark nil})]))
            (when (and s-key? (or active (not inp?)))
              (log "saving" buffer)
              (save-buffer! buffer)
              (om/transact! this `[(buffer/clear)]))
            (when (and z-key? (or active (not inp?)))
              (om/transact! this `[(durable/update {:z/active ~(not active)}) (z/put {:mark/mark nil})])))))))
  (componentWillUnmount [this]
    (-> js/window j/$ (.unbind ".zelector"))
    (-> js/document j/$ (.unbind ".zelector")))
  (render [this]
    (let [{:keys [:mark/ch :mark/over :mark/mark :flag/frozen :buffer]} (om/props this)
          {{:keys [:z/enabled :z/active] :or {enabled false active false}} :durable} (om/props this)
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
              (when-not mark
                (over-els this :over over))
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
(defn- handle-config-delta!
  "Handle durable config changes coming from the bg. This involves
  updating our state as well as ensuring other state changes that
  should be affected (e.g., clearing marks, etc.)"
  [durable-config]
  (let [st (deref (om.next/app-state reconciler))
        chg (first (data/diff durable-config (:durable st)))]
    ; merge! wipes out root properties, so until we customize merging
    ;   we are relying on incoming config delta/object containing full
    ;   set of known durable props.
    (om.next/merge! reconciler
      (merge {:durable durable-config}
        (if (some false? [(:z/enabled chg) (:z/active chg)])
          {:mark/mark nil})))))

; handle messages like, e.g.,
;   {action: "config",
;    params: {z/enabled: true,
;             z/active: true}}
; note: any "config" actions we'll merge the data directly into
;       our application/reconciler :durable state state.
(defn- handle-message! [msg]
  (let [{:keys [action params]} (util/js->clj* msg)]
    (case action
      "config" (handle-config-delta! params)
      nil)))

(defn- backgound-connect! []
  (let [port (bgx/connect!)]
    (go-loop []
      (when-let [msg (<! port)]
        (handle-message! msg)
        (recur)))))

; --- lifecycle ---
(def ^:dynamic *css-url-fa* "css/fa/css/font-awesome.min.css")
(def ^:dynamic *css-url-ze* "css/zelector.css")

(defn- install-glass-host! []
  (-> (j/$ "<div id=\"zelector-glass-host\">")
    (.appendTo "body")
    (aget 0))) ; (.attachShadow #js {:mode "closed"}) note: react hates the shadow dom.

(defn- install-css! [root-elem]
  (let [templ "<link rel=\"stylesheet\" type=\"text/css\" href=\"%s\">"]
    (doto (j/$ root-elem)
      (.append (gstr/format templ *css-url-fa*))
      (.append (gstr/format templ *css-url-ze*)))))

(defn- install-glass-mount! [root-elem]
  (do
    (install-css! root-elem)
    (-> (j/$ "<div id=\"zelector-glass-mount\">")
      (.appendTo (j/$ root-elem))
      (aget 0))))

(defn init! []
  (backgound-connect!)
  (binding [*css-url-fa* (get-url "css/fa/css/font-awesome-chrome-ext.min.css")
            *css-url-ze* (get-url "css/zelector.css")]
    (om/add-root! reconciler Zelector
      (install-glass-mount! (install-glass-host!)))))

; --- for sandbox ---
(defn init-basic!
  "Init headless (feetless?), i.e., w/o a background page or browser
  extension environment."
  []
  (bgx/connect-null!)
  (om.next/merge! reconciler {:durable {:z/enabled true :z/active true}})
  (om/add-root! reconciler Zelector
    (install-glass-mount! (install-glass-host!))))

(defn destroy-basic! []
  (let [host (gdom/getElement "zelector-glass-host")
        mount (gdom/getElement "zelector-glass-mount")]
    (om/remove-root! reconciler mount)
    (gdom/removeNode host)))
