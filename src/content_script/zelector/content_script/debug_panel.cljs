(ns zelector.content-script.debug-panel
  (:require [chromex.logging :refer-macros [log info warn error group group-end]]
            [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]
            [goog.string :as gstr]
            [goog.string.format]
            [zelector.common.util :as util]
            [zelector.common.trav :as trav]))

(defui DebugInfo
  Object
  (render [this]
    (let [{:keys [captured/range over char frozen partition-range-fn]} (om/props this)]
      (dom/div #js {:id      "zelector-debug"
                    :onClick #(.stopPropagation %)}
        (letfn [(fmt [[[sc so] [ec eo] :as range]]
                  (if range
                    (let [text (trav/range->str range) len (count text)]
                      (list
                        (str "[" char "]")
                        (dom/br #js {:key "1a"})
                        "---"
                        (dom/br #js {:key "1b"})
                        (gstr/format
                          "%s[%s]...%s[%s]"
                          (util/pretty-node (util/parent-node sc))
                          (util/node->sibling-index sc)
                          (util/pretty-node (util/parent-node ec))
                          (util/node->sibling-index ec))
                        (dom/br #js {:key "2a"})
                        "---"
                        (dom/br #js {:key "2b"})
                        (dom/span #js {:key   "3a"
                                       :style #js {:fontSize 8
                                                   :verticalAlign "sub"}} so)
                        (if (< len 50)
                          text (str (subs text 0 25) "..." (subs text (- len 25))))
                        (dom/span #js {:key   "3b"
                                       :style #js {:fontSize 8
                                                   :verticalAlign "sub"}} eo)
                        (dom/br #js {:key "4a"})
                        "---"
                        (dom/br #js {:key "4b"})
                        (dom/div
                          #js {:key "5z"
                               :className "zelector-debug-breakdown"}
                          (map-indexed
                            (fn [idx [[sc so] [ec eo] :as split]]
                              (list
                                "["
                                (dom/span #js {:key (str idx "a")
                                               :style #js {:fontSize 8
                                                           :verticalAlign "sub"}} so)
                                (trav/range->str split)
                                (dom/span #js {:key (str idx "b")
                                               :style #js {:fontSize 8
                                                           :verticalAlign "sub"}} eo)
                                (dom/span #js {:key (str idx "c")
                                               :style #js {:fontSize 8
                                                           :verticalAlign "super"}}
                                  (str (trav/valid-range? split)))
                                "]"))
                            (partition-range-fn range)))
                        (dom/br #js {:key "5a"})
                        "---"
                        (dom/br #js {:key "5b"})
                        (dom/div #js {:key "5"
                                      :onClick
                                      (fn [] (om/transact! this `[(debug/toggle-freeze)]))}
                          (if frozen "FROZEN" "ACTIVE"))))))]
          (fmt (or range over)))))))
