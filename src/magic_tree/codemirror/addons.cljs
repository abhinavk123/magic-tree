(ns magic-tree.codemirror.addons
  (:require [cljsjs.codemirror]
            [cljs.core.match :refer-macros [match]]
            [fast-zip.core :as z]
            [goog.events :as events]

            [magic-tree.core :as tree]
            [magic-tree.codemirror.util :as cm]
            [magic-tree.edit :refer [key-map]]))

(specify! (.-prototype js/CodeMirror)
  ILookup
  (-lookup
    ([this k] (get (.-$cljs$state this) k))
    ([this k not-found] (get (.-$cljs$state this) k not-found)))
  ISwap
  (-swap!
    ([this f] (set! (.-$cljs$state this) (f (.-$cljs$state this))))
    ([this f a] (set! (.-$cljs$state this) (f (.-$cljs$state this) a)))
    ([this f a b] (set! (.-$cljs$state this) (f (.-$cljs$state this) a b)))
    ([this f a b xs]
     (set! (.-$cljs$state this) (apply f (concat (list (.-$cljs$state this) a b) xs))))))

(.defineOption js/CodeMirror "cljsState" false
               (fn [cm] (set! (.-$cljs$state cm) (or (.-$cljs$state cm) {}))))

(defn clear-highlight! [cm]
  (doseq [handle (get-in cm [:magic/highlight :handles])]
    (.clear handle))
  (swap! cm dissoc :magic/highlight))

(defn highlight-node! [cm node]
  (when (and (not= node (get-in cm [:magic/highlight :node]))
             (not (.somethingSelected cm))
             (tree/sexp? node))
    (clear-highlight! cm)
    (swap! cm assoc :magic/highlight
           {:node    node
            :handles (cm/mark-ranges! cm (tree/node-highlights node) #js {:className "CodeMirror-eval-highlight"})})))

(defn update-highlights! [cm e]
  (let [{{bracket-loc :bracket-loc} :magic/cursor
         zipper                     :zipper} cm]

    (match [(.-type e) (.-which e) (.-metaKey e)]
           ["mousemove" _ true] (highlight-node! cm (->> (cm/mouse-pos cm e)
                                                         (tree/node-at zipper)
                                                         tree/mouse-eval-region
                                                         z/node))
           ["keyup" 91 false] (clear-highlight! cm)
           ["keydown" _ true] (highlight-node! cm (z/node bracket-loc))
           :else nil)))

(defn clear-brackets! [cm]
  (doseq [handle (get-in cm [:magic/cursor :handles])]
    (.clear handle))
  (swap! cm update :magic/cursor dissoc :handles))

(defn match-brackets! [cm node]
  (let [prev-node (get-in cm [:magic/cursor :node])]
    (when (not= prev-node node)
      (clear-brackets! cm)
      (when (tree/may-contain-children? node)
        (swap! cm assoc-in [:magic/cursor :handles]
               (cm/mark-ranges! cm (tree/node-highlights node) #js {:className "CodeMirror-matchingbracket"}))))))

(defn update-ast!
  [{:keys [ast] :as cm}]
  (when-let [next-ast (try (tree/ast (.getValue cm))
                           (catch js/Error e (.debug js/console e)))]
    (when (not= next-ast ast)
      (swap! cm assoc
             :ast next-ast
             :zipper (tree/ast-zip next-ast)))))

(defn update-cursor!
  [{:keys [zipper magic/brackets?] :as cm}]
  (let [position (cm/cursor-pos cm)]
    (when-let [loc (and zipper
                        (some->> position
                                 (tree/node-at zipper)))]
      (let [bracket-loc (tree/nearest-bracket-region loc)
            bracket-node (z/node bracket-loc)]
        (when brackets? (match-brackets! cm bracket-node))
        (swap! cm update :magic/cursor merge {:loc          loc
                                              :node         (z/node loc)
                                              :bracket-loc  bracket-loc
                                              :bracket-node bracket-node
                                              :pos          position})))))

(defn require-opts [cm opts]
  (doseq [opt opts] (.setOption cm opt true)))

(.defineOption js/CodeMirror "magicTree" false
               (fn [cm on?]
                 (when on?
                   (require-opts cm ["cljsState"])
                   (.on cm "change" update-ast!))))

(.defineOption js/CodeMirror "magicCursor" false
               (fn [cm on?]
                 (when on?
                   (require-opts cm ["magicTree"])
                   (.on cm "cursorActivity" update-cursor!))))

(.defineOption js/CodeMirror "magicBrackets" false
               (fn [cm on?]
                 (when on?
                   (require-opts cm ["magicCursor"])

                   (cm/define-extension "magicClearHighlight" clear-highlight!)
                   (cm/define-extension "magicUpdateHighlight" update-highlights!)

                   (.on cm "keyup" update-highlights!)
                   (.on cm "keydown" update-highlights!)
                   (when-let [dom-node (.. cm -display -wrapper)]
                     (events/listen dom-node "mousemove" (partial update-highlights! cm)))

                   (swap! cm assoc :magic/brackets? true))))

(.defineOption js/CodeMirror "magicEdit" false
               (fn [cm kmap]
                 (when kmap
                   (.addKeyMap cm (clj->js (cond-> key-map
                                                   (map? kmap) (merge kmap)))))))

