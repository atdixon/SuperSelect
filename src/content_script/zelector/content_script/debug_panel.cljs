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
        (letfn [(layout [[[sc so] [ec eo] :as range]]
                  (if range
                    (let [text (trav/range->str range) len (count text)]
                      (list
                        (dom/div #js {:key "A"} (dom/i nil "char = ") char)
                        (dom/div #js {:key "B"}
                          (dom/i #js {:key "a"} "word = ")
                          (dom/span #js {:key "b"
                                         :style #js {:fontSize 8
                                                     :verticalAlign "sub"}}
                            (gstr/format "[%s.tns[%d],%d]"
                              (util/pretty-node (util/parent-node sc))
                              (util/node->sibling-index sc)
                              so))
                          (if (< len 50)
                            text (str (subs text 0 25) "..." (subs text (- len 25))))
                          (dom/span #js {:key "c"
                                         :style #js {:fontSize 8
                                                     :verticalAlign "sub"}}
                            (gstr/format "[%s.tns[%d],%d]"
                              (util/pretty-node (util/parent-node ec))
                              (util/node->sibling-index ec)
                              eo)))
                        (dom/div
                          #js {:key "C"
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
                                  (str (if (trav/valid-range? split) "valid" "invalid")))
                                "]"))
                            (partition-range-fn range)))
                        (dom/div #js {:key "D"
                                      :onClick
                                      (fn [] (om/transact! this `[(z/put {:flag/frozen ~(not frozen)})]))}
                          (if frozen "FROZEN" "ACTIVE"))))))]
          (layout (or range over)))))))
