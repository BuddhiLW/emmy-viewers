(ns ^:no-doc emmy.viewer.raster.glue
  "Pure layer of the raster backend, third stage: the JavaScript that loads a
  plan's compiled modules and calls them in the calling convention Emmy's `:js`
  mode would have produced, so a viewer cannot tell which backend compiled its
  function. Strings only; nothing is evaluated here."
  (:require [clojure.string :as str]))

(def sync-limit
  "The largest module, in bytes, that browsers compile synchronously on the main
  thread. A larger module is compiled asynchronously, and calls go to the `:js`
  fallback until it is ready."
  4096)

(defn- js-out
  "The JS expression of an output of shape `shape`, calling the kernels with
  `args` (a JS argument list as a string)."
  [shape args]
  (cond
    (nil? shape)    (str "ks[0](" args ")")
    (vector? shape) (str "[" (str/join ", " (map #(js-out % args) shape)) "]")
    :else           (str "ks[" shape "](" args ")")))

(def ^:private prelude
  (str/join
   "\n"
   ["const flat = (x, out) => {"
    "  if (typeof x === \"number\") { out.push(x); }"
    "  else if (x != null) { for (const v of x) flat(v, out); }"
    "  return out;"
    "};"
    "const bytes = (b) => Uint8Array.from(atob(b), (c) => c.charCodeAt(0));"
    "const load = (m) => new WebAssembly.Instance(m, {}).exports.k;"
    "let ks = null;"]))

(defn- loader
  "JS that fills `ks` with the kernels of `modules` (base64 strings),
  synchronously when `sync?`."
  [modules sync?]
  (let [srcs (str "[" (str/join ", " (map pr-str modules)) "]")]
    (str "if (typeof WebAssembly !== \"undefined\") {\n"
         (if sync?
           (str "  try { ks = " srcs ".map((b) => load(new WebAssembly.Module(bytes(b)))); }\n"
                "  catch (e) { ks = null; }\n")
           (str "  Promise.all(" srcs ".map((b) => WebAssembly.compile(bytes(b))))\n"
                "    .then((ms) => { ks = ms.map(load); })\n"
                "    .catch(() => { ks = null; });\n"))
         "}")))

(defn- function-source
  "The JS function that calls the kernels in `plan`'s calling convention, and
  `fb` while they are not loaded. Kernels take the flattened state, then the
  flattened parameters."
  [{:keys [convention state params outputs shape]}]
  (let [inputs (fn [state-arg]
                 (str "  const a = flat(" state-arg ", [])"
                      (when (seq params) ".concat(flat(ps, []))")
                      ";\n"))]
    (case convention
      :native
      (let [xs (str/join ", " state)]
        (str "const f = function(" xs ") {\n"
             "  if (ks === null) return fb(" xs ");\n"
             "  return " (js-out shape xs) ";\n"
             "};"))

      :structure
      (str "const f = function(state, ps) {\n"
           "  if (ks === null) return fb(state, ps);\n"
           (inputs "state")
           "  return " (js-out shape "...a") ";\n"
           "};")

      :primitive
      (str "const f = function(ys, yps, ps) {\n"
           "  if (ks === null) return fb(ys, yps, ps);\n"
           (inputs "ys")
           (str/join (map (fn [i] (str "  yps[" i "] = ks[" i "](...a);\n"))
                          (range (count outputs))))
           "};"))))

(defn glue
  "The source of a JS function of one argument, `fb` (the same function compiled
  by Emmy's `:js` mode), that loads `modules` (base64 wasm, one per output of
  `plan`) and returns a function in `plan`'s calling convention. The returned
  function answers `wasm()` with whether the kernels are loaded."
  [plan modules sync?]
  (str/join "\n" [prelude
                  (loader modules sync?)
                  (function-source plan)
                  "f.wasm = () => ks !== null;"
                  "return f;"]))
