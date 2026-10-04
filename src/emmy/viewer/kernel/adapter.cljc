(ns ^:no-doc emmy.viewer.kernel.adapter
  "Pure source generator for the `:js` backend's side of the Kernel contract.

  [[adapter-source]] reads a Plan's data (see `emmy.viewer.kernel.plan`) and
  returns JS that takes Emmy's `:js` compiled function and attaches `batch`,
  `ready` and `dims` to that same function, so per-point calls keep running
  Emmy's code untouched while batch callers see the contract raster kernels
  satisfy. Strings only; nothing is evaluated here."
  (:require [clojure.string :as str]))

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
