(ns ^:no-doc emmy.viewer.kernel.adapter
  "Pure source generator for the JavaScript kernel contract. It consumes only the
  data of a Plan, not kernel.plan itself: that namespace is JVM-only, while
  compile.cljc and this adapter must load in Portal's cljs build. The JVM
  backend builds the plan; cljs callers can pass the same plain data."
  (:require [clojure.string :as str]
            [emmy.structure :as s]))

(defn cljs-plan
  "Shape-only plan for cljs, where the JVM kernel.plan namespace is unavailable.
  Applies the function to symbolic state/parameters exactly once, without
  simplification, so the JavaScript adapter can be generated in Portal too."
  [f params initial-state {:keys [calling-convention generic-params?]
                           :or {calling-convention :structure
                                generic-params? (boolean params)}}]
  (let [tree? (fn [x] (or (vector? x) (s/structure? x)))
        leaves (fn leaves [x]
                 (if (tree? x) (mapcat leaves x) [x]))
        shape (fn [x]
                (when (tree? x)
                  (let [i (volatile! -1)]
                    ((fn walk [y]
                       (if (tree? y) (mapv walk y) (vswap! i inc))) x))))
        i (volatile! -1)
        state ((fn walk [x]
                 (if (tree? x)
                   (mapv walk x)
                   (symbol (str "s" (vswap! i inc))))) initial-state)
        ps (when (and params generic-params?)
             (mapv #(symbol (str "p" %)) (range (count params))))
        g (cond (false? params) f
                generic-params? (apply f ps)
                :else (apply f params))
        out (if (= calling-convention :native) (apply g state) (g state))]
    {:convention calling-convention
     :state (vec (leaves state)) :state-shape (shape initial-state)
     :outputs (vec (leaves out)) :shape (shape out)
     :params (or ps [])}))

(defn- state-expression
  "JavaScript expression rebuilding a state from a flattened input row."
  [shape]
  (if (vector? shape)
    (str "[" (str/join ", " (map state-expression shape)) "]")
    (str "xs[r" (when (pos? shape) (str " + " shape)) "]")))

(defn- output-writes
  "Assignments of all output leaves, in depth-first row-major order."
  [shape expr col]
  (if (vector? shape)
    (str/join "\n" (map-indexed (fn [i child]
                                   (output-writes child (str expr "[" i "]") col))
                                 shape))
    (str "    out[col" (when (pos? shape) (str " + " shape)) "] = " expr ";")))

(defn adapter-source
  "Returns JS source for a function accepting Emmy's compiled function `fb` and
  returning that SAME function with batch, ready and dims attached. The point
  path remains Emmy's original function, without any wrapper or allocation."
  [{:keys [convention state state-shape outputs shape params]}]
  (let [d (count state)
        m (count outputs)
        result-shape (if (nil? shape) 0 shape)
        call (case convention
               :native (str "fb(" (str/join ", " (map (fn [j]
                                                        (state-expression j))
                                                      (range d))) ")")
               :structure (str "fb(" (state-expression (if (nil? state-shape) 0 state-shape)) ", ps)")
               :primitive nil
               (throw (ex-info (str "Unknown kernel calling convention " convention)
                               {:convention convention})))
        body (if (= convention :primitive)
               (str "    const row = typed ? xs.subarray(r, r + " d ") : scratch;\n"
                    "    if (!typed) for (let j = 0; j < " d "; j++) row[j] = xs[r + j];\n"
                    "    fb(row, out.subarray(col, col + " m "), ps);")
               (str "    const value = " call ";\n"
                    (output-writes result-shape "value" "col")))]
    (str/join "\n"
              ["fb.batch = function(xs, n, ps, out) {"
               (str "  out = out || new Float64Array(n * " m ");")
               (when (= convention :primitive)
                 (str "  const typed = ArrayBuffer.isView(xs) && typeof xs.subarray === 'function';\n"
                      "  const scratch = typed ? null : new Float64Array(" d ");"))
               (str "  for (let i = 0; i < n; i++) {\n"
                    "    const r = i * " d ", col = i * " m ";\n"
                    body "\n  }")
               "  return out;"
               "};"
               "fb.ready = () => true;"
               (str "fb.dims = {state: " d ", outputs: " m ", params: " (count params) "};")
               "return fb;"])))
