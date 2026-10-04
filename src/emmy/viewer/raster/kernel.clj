(ns ^:no-doc emmy.viewer.raster.kernel
  "Pure layer of the raster backend: an Emmy function becomes a kernel plan, the
  raster source of each kernel, and the JavaScript glue that calls the compiled
  modules. Nothing here evaluates a form or touches raster itself; see
  [[emmy.viewer.raster]] for the boundary.

  Emmy does the algebra. A function is applied to symbolic arguments exactly as
  [[emmy.expression.compile/compile-state-fn]] would apply it, simplified, and
  split into one scalar expression per output component. Each component becomes
  a `raster.core/deftm` of `Double` arguments (state first, then parameters) to
  `Double`, which raster compiles to its own import-free WebAssembly module.

  The glue gives the modules the calling convention Emmy's `:js` mode would
  have produced, so a viewer cannot tell which backend compiled its function."
  (:require [clojure.string :as str]
            [emmy.env :as e]
            [emmy.structure :as s]))

;; ## Symbolic application

(defn- tree?
  "True for the containers whose leaves are separate components: Emmy
  structures and Clojure vectors."
  [x]
  (or (s/structure? x) (vector? x)))

(defn leaves
  "The scalar leaves of `x` in depth-first order, the order Emmy uses to flatten a
  state for the `:primitive` calling convention."
  [x]
  (if (tree? x)
    (into [] (mapcat leaves) x)
    [x]))

(defn shape
  "The tree of `x` with each leaf replaced by its index in [[leaves]], or `nil`
  when `x` is itself a scalar."
  [x]
  (when (tree? x)
    (let [counter (volatile! -1)
          walk    (fn walk [y]
                    (if (tree? y)
                      (mapv walk y)
                      (vswap! counter inc)))]
      (walk x))))

(defn- symbolize
  "`x` with every leaf replaced by a fresh symbol `<prefix><i>`, in [[leaves]]
  order. Vectors stay vectors and Emmy structures stay structures, so `f` sees
  the same shape it would see from Emmy's compiler."
  [x prefix]
  (let [counter (volatile! -1)
        fresh   #(symbol (str prefix (vswap! counter inc)))
        walk    (fn walk [y]
                  (cond (vector? y)       (mapv walk y)
                        (s/structure? y)  (s/mapr (fn [_] (fresh)) y)
                        :else             (fresh)))]
    (walk x)))

(defn plan
  "Applies `f` symbolically the way [[emmy.expression.compile/compile-state-fn]]
  does with the same `params`, `initial-state` and `opts`, and returns:

  - `:convention`: `:native`, `:structure` or `:primitive`
  - `:state`: the state symbols, flattened
  - `:params`: the parameter symbols, empty when the parameters are not generic
  - `:outputs`: one frozen, simplified expression per output component
  - `:shape`: see [[shape]]; `nil` for a scalar output"
  [f params initial-state {:keys [calling-convention generic-params? simplify?]
                           :or   {calling-convention :structure
                                  generic-params?    (boolean params)
                                  simplify?          true}}]
  (let [state      (symbolize initial-state "s")
        param-syms (when (and params generic-params?)
                     (mapv #(symbol (str "p" %)) (range (count params))))
        g          (cond (false? params)  f
                         generic-params?  (apply f param-syms)
                         :else            (apply f params))
        out        (if (= calling-convention :native)
                     (apply g state)
                     (g state))
        out        (if simplify? (e/simplify out) out)]
    {:convention calling-convention
     :state      (leaves state)
     :params     (or param-syms [])
     :outputs    (mapv e/freeze (leaves out))
     :shape      (shape out)}))

;; ## Lowering to raster's vocabulary

(def ^:private raster-fns
  "Emmy operators raster provides directly. `sqrt` and `abs` are JVM intrinsics
  that raster lowers to `f64.sqrt` and `f64.abs`."
  '{sin  raster.math/sin  cos  raster.math/cos  tan  raster.math/tan
    asin raster.math/asin acos raster.math/acos atan raster.math/atan
    sinh raster.math/sinh cosh raster.math/cosh tanh raster.math/tanh
    exp  raster.math/exp  log  raster.math/log
    sqrt Math/sqrt        abs  Math/abs})

(defn- binary
  "A variadic application as nested binary ones; raster's typed dispatch
  resolves `+` and `*` one pair at a time."
  [op args]
  (reduce (fn [acc a] (list op acc a)) args))

(defn- power
  "`base` to the literal `k`. An integer `k` becomes multiplication, since
  raster's wasm `pow` is `exp(k log x)` and undefined for a negative base."
  [base k]
  (cond
    (and (integer? k) (zero? k)) 1.0
    (and (integer? k) (pos? k))  (binary '* (repeat k base))
    (integer? k)                 (list '/ 1.0 (power base (- k)))
    :else                        (list 'raster.numeric/pow base (double k))))

(defn- exponent
  "A literal exponent as an integer when it is integral, else as is."
  [k]
  (if (and (not (integer? k)) (== k (Math/rint (double k))))
    (long k)
    k))

(defn- unsupported! [x]
  (throw (ex-info (str "The raster backend cannot compile " (pr-str x)
                       "; use the :js backend for this function.")
                  {:form x})))

(defn- lower-node
  "Lowers one node whose arguments are already lowered."
  [args x]
  (cond
    (double? x)  x
    (number? x)  (double x)
    (symbol? x)  (if (args x) x (unsupported! x))
    (seq? x)
    (let [[op & xs] x]
      (case op
        (+ *)  (if (next xs) (binary op xs) (first xs))
        -      (if (next xs) (binary '- xs) (list '- 0.0 (first xs)))
        /      (if (next xs) (binary '/ xs) (list '/ 1.0 (first xs)))
        square (power (first xs) 2)
        cube   (power (first xs) 3)
        ;; Emmy's two-argument atan is atan2, with the same argument order.
        atan   (if (next xs)
                 (list 'raster.math/atan2 (first xs) (second xs))
                 (list 'raster.math/atan (first xs)))
        cot    (list '/ 1.0 (list 'raster.math/tan (first xs)))
        sec    (list '/ 1.0 (list 'raster.math/cos (first xs)))
        csc    (list '/ 1.0 (list 'raster.math/sin (first xs)))
        (if-let [f (and (not (next xs)) (raster-fns op))]
          (list f (first xs))
          (unsupported! x))))
    :else (unsupported! x)))

(defn lower
  "Lowers a frozen Emmy expression over the symbols in `args` to the vocabulary
  `raster.core/deftm` compiles: every literal a double, `+` and `*` binary,
  integer powers as products. Throws for an operator or a free symbol raster
  cannot compile."
  [args expr]
  (let [args (set args)]
    (letfn [(go [x]
              (if (seq? x)
                (let [[op & xs] x]
                  (if (= 'expt op)
                    ;; A literal exponent reaches `power` unlowered, so an
                    ;; integer stays an integer.
                    (let [[b k] xs]
                      (if (number? k)
                        (power (go b) (exponent k))
                        (list 'raster.numeric/pow (go b) (go k))))
                    (lower-node args (cons op (map go xs)))))
                (lower-node args x)))]
      (go expr))))

(defn kernel-form
  "The `raster.core/deftm` source of a scalar kernel named `kname` over `args`."
  [kname args body]
  (list 'raster.core/deftm kname
        (into [] (mapcat (fn [a] [a :- 'Double])) args)
        :- 'Double
        body))

(defn kernel-forms
  "One kernel source per output of `plan`, named by `kname-fn` of its index."
  [{:keys [state params outputs]} kname-fn]
  (let [args (into (vec state) params)]
    (vec
     (map-indexed (fn [i out]
                    (kernel-form (kname-fn i) args (lower args out)))
                  outputs))))

;; ## JavaScript glue

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
