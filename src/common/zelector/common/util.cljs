(ns zelector.common.util
  (:require [chromex.logging :refer-macros [log info warn error group group-end]]
            [clojure.string :as str]
            [clojure.walk :as walk :refer [keywordize-keys]]
            [cljsjs.jquery]
            [jayq.core :as j]))

; --- basic ---
(defn any [coll]
  "Answers any item from coll. If coll is nil,
  returns nil."
  (first coll))

(defn not-blank [str]
  (if-not (str/blank? str) str))

(defn whitespace-char? [ch]
  (contains? (set " \f\n\r\u00A0\u2028\u2029") ch))

(defn letter-char? [ch]
  (not (nil? (re-matches #"(?i)[a-z]" (str ch)))))

(defn punctuation-char? [ch]
  (not
    (nil?
      (re-matches
        #"[\u2000-\u206F\u2E00-\u2E7F\\'!\"#$%&()*+,\-./:;<=>?@\[\]^_`{|}~]"
        (str ch)))))

(defn resettable-memoize [f]
  (let [mem (atom {})]
    (fn [& args]
      (if (= (first args) :reset!)
        (reset! mem {})
        (if-let [e (find @mem args)]
          (val e)
          (let [ret (apply f args)]
            (swap! mem assoc args ret)
            ret))))))

; --- js/clj ---
(defn- keyword->fqn [kw]
  (if-let [ns (namespace kw)]
    (str ns "/" (name kw))
    (name kw)))

(extend-type Keyword
  IEncodeJS
  (-clj->js [x] (keyword->fqn x))
  (-key->js [x] (keyword->fqn x)))

(defn js->clj* [val]
  (keywordize-keys (js->clj val)))

; --- dom ---
(extend-type js/NodeList
  ISeqable
  (-seq [node-list] (array-seq node-list)))

(extend-type js/ClientRectList
  ISeqable
  (-seq [rect-list] (array-seq rect-list)))

(defn node-type [node]
  (.-nodeType node))

(defn child-nodes [node]
  (.-childNodes node))

(defn prev-sibling [node]
  (.-previousSibling node))

(defn next-sibling [node]
  (.-nextSibling node))

(defn parent-node [node]
  (.-parentNode node))

(defn text-content [node]
  (.-textContent node))

(defn tag-name [node]
  (.-tagName node))

(defn start-container [html-range]
  (.-startContainer html-range))

(defn end-container [html-range]
  (.-endContainer html-range))

(defn start-offset [html-range]
  (.-startOffset html-range))

(defn end-offset [html-range]
  (.-endOffset html-range))

(defn node-list->array [node-list]
  (.from js/Array (.values node-list)))

(defn node->sibling-index [node]
  (.indexOf (node-list->array (child-nodes (parent-node node))) node))

(defn leaf-node? [node]
  (empty? (child-nodes node)))

(defn element-node? [node]
  (= (node-type node) 1))

(defn text-node? [node]
  (= (node-type node) 3))

(defn body-node? [node]
  (and (element-node? node)
       (= "body" (.toLowerCase (tag-name node)))))

(defn input-node? [node]
  (and (element-node? node)
       (= "input" (.toLowerCase (tag-name node)))))

(defn visible-node? [node]
  (if (text-node? node)
    (recur (parent-node node))
    (j/is (j/$ node) ":visible")))

(defn point->caret-position
  "Given client (x,y) point, answer position as [container offset] or nil
  if (x,y) is not within text node."
  [x y]
  (when-let [range (.caretRangeFromPoint js/document x y)]
    [(.-startContainer range) (.-startOffset range)]))

(defn- str->camel-case [str]
  (let [words (str/split str #"[\s_-]+")]
    (str/join
      ""
      (cons (str/lower-case (first words)) (map str/capitalize (rest words))))))

(defn elem->text-styles [elem]
  (let [$elem (j/$ elem)
        css-props ["font-style" "font-variant" "font-weight" "font-stretch"
                   "font-size" "line-height" "font-family"
                   ; text-, text layout
                   "text-transform" "text-decoration" "text-shadow"
                   "text-align" "line-height" "letter-spacing" "word-spacing"
                   ; more font styles
                   "font-variant" "font-kerning" "font-feature-settings"
                   "font-variant-caps" "font-variant-east-asian" "font-variant-ligatures"
                   "font-variant-numeric" "font-variant-position" "font-size-adjust"
                   "font-stretch" "text-underline-position" "text-rendering"
                   ; text layout styles
                   "text-indent" "word-break" "text-overflow" "white-space"
                   "direction" "hyphens" "line-break" "text-align-last"
                   "text-orientation" "word-wrap" "writing-mode"]]
    (into {} (filter (fn [[_ val]] (not (nil? val)))
                     (map #(vector (-> % str->camel-case keyword) (.css $elem %)) css-props)))))

; --- pretty ---
(defn pretty-node [node]
  (if (seq? node)
    (str/join "," (map pretty-node node))
    (let [node-type (if-not (nil? node) (node-type node))]
      (case node-type
        1 (str (.toLowerCase (j/prop (j/$ node) "tagName"))
               (or
                 (if-not (str/blank? (j/prop (j/$ node) "id")) (str "#" (j/prop (j/$ node) "id")))
                 (if-not (str/blank? (j/prop (j/$ node) "class")) (str "." (j/prop (j/$ node) "class")))
                 ""))
        3 (if (str/blank? (text-content node))
            "\"<blank>\""
            (str "\"" (text-content node) "\""))
        nil "<nil>"
        (str "<" node-type ">")))))
