(ns zelector.common.trav
  "DOM traversal functions. Generally parameters and return vals are cljs
  data structures (vectors etc) instead of js objects (e.g. js/Range)."
  (:require [zelector.common.util :as util]
            [clojure.string :as str]
            [chromex.logging :refer-macros [log info warn error group group-end]]
            [cljsjs.jquery]
            [jayq.core :as j]))

; --- range support ---
(defn range->clj
  "Convert js/Range to cljs."
  [js-range]
  ((juxt (juxt util/start-container util/start-offset)
     (juxt util/end-container util/end-offset)) js-range))

(def range->js
  "Convert cljs range to js/Range."
  (memoize
    (fn [[[sc so] [ec eo]]]
      (doto (.createRange js/document)
        (.setStart sc so)
        (.setEnd ec eo)))))

(defn empty-range?
  "Given range [[start-container start-offset] [end-container end-offset]],
  is the range empty?"
  [[s e]] (= s e))

(defn range->str
  "Convert range to string. May include non-visible text."
  [range]
  (-> range range->js .toString))

(defn range->client-rects [range]
  (-> range range->js .getClientRects array-seq))

(defn range->parent-node
  "Answers parent node of start container's parent."
  [range]
  (util/parent-node (ffirst range)))

(defn valid-range?
  "True iff range endpoints are identical or the end follows the start."
  [[[sc so] [ec eo] :as range]]
  (or
    (= [sc so] [ec eo])
    (= 1
      (.comparePoint
        (range->js [[sc so] [sc so]]) ec eo))))

; --- traversal &c ---
(defn node->leaves
  "Answer seq of leaves descendant from provided html node, ordered
  from left to right."
  [node]
  (-> node
    (j/$)
    ; note: no need for iframes; which can yield xdomain security errs anyway.
    (j/find ":not('iframe')")
    (.addBack)
    (.contents)
    (.addBack)
    (.filter #(util/leaf-node? %2))
    (.toArray)
    (array-seq)))

(defn leaves-from
  "Answer lazy seq of leaf nodes starting at the provided from node
  (but *excluding* it), stopping at optional root barrier."
  [root from direction]
  (if (= root from)
    nil
    (lazy-seq
      (let [sibling-fn (direction {:left util/prev-sibling :right util/next-sibling})
            sibling-order-fn (direction {:left reverse :right identity})
            sibling (sibling-fn from)
            parent (util/parent-node from)]
        (cond
          sibling (if (util/text-node? sibling)
                    (cons
                      sibling
                      (leaves-from root sibling direction))
                    (concat
                      (sibling-order-fn (node->leaves sibling))
                      (leaves-from root sibling direction)))
          parent (leaves-from root parent direction))))))

(def visible-text-node?
  "Visible text node? Effectively defines the class of text nodes that we
  wish to consider when traversing or manipulating nodes and ranges."
  (every-pred util/text-node? util/visible-node?
    (comp (complement empty?) util/text-content)))

(defn next-text-nodes
  "Answer lazy seq of *visible*, non-empty text nodes from provided node
   (but *excluding* provided node), stopping at optional root barrier."
  ([from direction]
   (next-text-nodes nil from direction))
  ([root from direction]
   (filter visible-text-node? (leaves-from root from direction))))

(defn +position
  "Given a position within a text-node, which may not index a specific
  character (i.e., it may indicate rightmost edge of a text-node), add
  n to produce new position (clipped at any natural barrier or optionally
  provided root barrier)."
  ([position n] (+position nil position n))
  ([root position n]
   {:pre [(visible-text-node? (position 0))]}
   (if (zero? n)
     position
     (let [[container offset] position
           container-text (util/text-content container)
           next-offset (+ offset n)
           container-text-len (count container-text)]
       (if (<= 0 next-offset container-text-len)
         ; next-offset is within container's text (or indicates
         ; rightmost edge of container); so give answer.
         (vector container next-offset)
         (let [direction (if (neg? next-offset) :left :right)
               next-node (first (next-text-nodes root container direction))
               next-n (if (neg? next-offset)
                        next-offset
                        (- next-offset container-text-len))
               next-position (if next-node
                               [next-node
                                (if (neg? next-offset)
                                  (count (util/text-content next-node)) 0)])]
           (if next-position
             (recur root next-position next-n)
             ; there is no next position; answer current container at appropriate edge
             (vector container (case direction :left 0 :right container-text-len)))))))))

(defn inc-position
  ([position] (inc-position nil position))
  ([root position]
   (+position root position +1)))

(defn dec-position
  ([position] (dec-position nil position))
  ([root position]
   (+position root position -1)))

(defn text-node->chars
  "Answer seq of characters given a text-node. The seq items are
   actually maps of the form {:char <ch> :position [<container> <offset>]}."
  [text-node]
  (map #(hash-map :char %2 :position [text-node %1])
    (iterate inc 0) (util/text-content text-node)))

(defn position->char-seq
  "Return lazy seq of {:char <ch> :position <pos>} starting at and *including*
   provided position (if it indexes a character), and stopping at provided root
   node."
  [root position direction]
  (if-let [[container index] position]
    (case direction
      :right (concat (drop index (text-node->chars container))
               (apply concat
                 (map text-node->chars
                   (next-text-nodes root container direction))))
      :left (concat (reverse (take (inc index) (text-node->chars container)))
              (apply concat
                (map (comp reverse text-node->chars)
                  (next-text-nodes root container direction)))))))

(defn count-chars-while
  "Count chars from position (*including* position if it indexes
  a character) while-pred."
  [root position direction while-pred]
  (count (take-while while-pred (position->char-seq root position direction))))

(defn +position-while
  "Increment position (within text node) until while-pred is false for the
  position (or natural or optional root barrier is reached). The result
  is a [container offset], not necessarily an index to a specific character.

  Note that the provided predicate should operate on values that are maps
  of the form {:char <ch> :position [<container> <index>]}."
  ([position direction while-pred]
   (+position-while nil position direction while-pred))
  ([root position direction while-pred]
   {:pre [(visible-text-node? (position 0))]}
   (let [n (count-chars-while root position direction while-pred)]
     (+position root position (case direction :left (- n) :right n)))))

(defn normalize-range*
  "If range is not empyt and its start offset is at the tail of its container,
  then bump/inc its start position into the next/adjacent container."
  [[[sc so] [ec eo] :as range]]
  {:pre [(visible-text-node? sc)
         (<= 0 so (count (util/text-content sc)))
         (visible-text-node? ec)
         (<= 0 eo (count (util/text-content ec)))]}
  (if (and
        (not (= sc ec))
        (= so (count (util/text-content sc))))
    (if-let [sc' (first (next-text-nodes sc :right))]
      [[sc' 0] [ec eo]]
      range)
    range))

(defn position->range
  "Given a position ([container index]), produce maximum range containing that position
   where while-pred is true for all characters to the left and right of that position.

   When pred(s) are not true for the initial position/character, the position is kept
   as-is within the answered range.

   Preds operate on {:char <ch> :position [<container> <index>]}."
  ([position while-pred]
   (position->range nil position while-pred))
  ([root position while-pred]
   (position->range root position while-pred while-pred))
  ([root [container index :as position] while-predl while-predr]
   {:pre [(visible-text-node? container)
          (< -1 index (count (util/text-content container)))]}
   (let [ch (.charAt (util/text-content container) index)]
     (normalize-range*
       [(if (while-predl {:char ch :position position})
          (inc-position
            (+position-while root position :left while-predl))
          position)
        (+position-while root position :right while-predr)]))))

(defn grow-ranger
  "Grow range to the right per char predicate."
  ([range while-pred] (grow-ranger nil range while-pred))
  ([root [sc ec] while-pred]
   [sc (+position-while root ec :right #(while-pred (:char %)))]))

(defn grow-rangel
  "Grow range to the left per char predicate."
  ([range while-pred] (grow-rangel nil range while-pred))
  ([root [sc ec] while-pred]
   [(inc-position
      (+position-while root (dec-position sc) :left #(while-pred (:char %)))) ec]))

(defn grow-range
  "Grow range in both directions per char predicate."
  ([range while-pred] (grow-range nil range while-pred))
  ([root range while-pred]
   (as-> range r
     (grow-ranger root r while-pred)
     (grow-rangel root r while-pred))))

(defn text-node->range
  "Produce range [[<container> <offset>] [<container> <offset>]] given
  text node and optionally start and end offsets."
  ([node] (text-node->range node 0))
  ([node start]
   (text-node->range node start (count (util/text-content node))))
  ([node start end]
   [[node start] [node end]]))

(defn split-range-by-text-node
  "Given range whose endpoints are text nodes, answer seq of ranges containing
  single text nodes only. Ranges at ends of seq may partially cover their text
  nodes, of course."
  [[[sc so] [ec eo] :as range]]
  {:pre [(visible-text-node? sc)
         (visible-text-node? ec)]}
  (if (= sc ec)
    (list range)
    (concat
      (cons
        (text-node->range sc so)
        (map text-node->range
          (take-while
            #(not= % ec)
            (next-text-nodes sc :right))))
      (list (text-node->range ec 0 eo)))))

(defn range->char-seq
  "Produce char seq from range whose endpoints are text nodes. Only includes
  visible text within the range."
  [range]
  (mapcat range->str (split-range-by-text-node range)))

(defn range-size [range]
  (count (range->char-seq range)))

(defn range-empty? [[s e]]
  (= s e))

(defn range-blank? [range]
  (every? util/whitespace-char? (range->char-seq range)))

(defn trim-range
  "Trim range using provided pred over single characters."
  ([range] (trim-range range util/whitespace-char?))
  ([[[sc so] [ec eo] :as range] while-pred]
   {:pre [(valid-range? range)
          (not (empty? (util/text-content ec)))]
    :post [(valid-range? %)]}
   (if (every? while-pred (range->char-seq range))
     [[sc so] [sc so]]
     [(+position-while [sc so] :right #(while-pred (:char %)))
      (inc-position
        (+position-while [ec (dec eo)] :left #(while-pred (:char %))))])))

(defn position->word-range
  "Given a position ([container index]), produce range that contains the whole word
  that contains that position."
  ([position]
   (position->word-range nil position))
  ([root [container offset :as position]]
   {:pre [(visible-text-node? container)]}
   (trim-range
     (position->range root position
       #(and
         (= container ((:position %) 0))
         (not (util/whitespace-char? (:char %)))))
     util/punctuation-char?)))

(defn combine-js-ranges
  "Create minimal cljs range that includes both provided (js) ranges."
  [range1 range2]
  (let [compare (.compareBoundaryPoints range1 (.-START_TO_START js/Range) range2)
        before (if (< compare 0) range1 range2)
        after (if (< compare 0) range2 range1)]
    [((juxt util/start-container util/start-offset) before)
     ((juxt util/end-container util/end-offset) after)]))

(defn combine-ranges
  "Create minimal range that includes both provided ranges."
  [range1 range2]
  (combine-js-ranges (range->js range1) (range->js range2)))

(defn halve-range
  "Split range in half; answer list of the two splits/ranges, either of which may
  be empty (i.e., contain no chars). Both ranges are guaranteed to be shorter than
  the provided range or empty."
  [[[sc so] [ec eo] :as range]]
  {:pre [(visible-text-node? sc)
         (visible-text-node? ec)]}
  (let [width (range-size range)
        half (Math/ceil (/ width 2))]
    (let [midpoint (+position [sc so] half)]
      (list [[sc so] midpoint] [midpoint [ec eo]]))))

(defn partition-range-by
  "Given range (with text node endpoints) split into individual text node
  ranges then recombine per partition-fn."
  ([range] (partition-range-by range range->parent-node))
  ([range partition-fn]
   (map
     #(reduce combine-ranges %)
     (partition-by
       partition-fn
       (split-range-by-text-node range)))))

(def partition-range-by*
  (util/resettable-memoize partition-range-by))

(def partition-range-with*
  "Answer seq of ranges which are subdivisions of the provided range that
  each match the provided pred."
  (util/resettable-memoize
    (fn [range pred]
      (cond
        (range-empty? range) ()
        (= (range-size range) 1) (list range)
        (pred range) (list range)
        :default (let [[left right] (halve-range range)]
                   (concat
                     (partition-range-with* left pred)
                     (partition-range-with* right pred)))))))
