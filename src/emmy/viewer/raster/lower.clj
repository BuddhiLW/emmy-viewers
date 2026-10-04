(ns ^:no-doc emmy.viewer.raster.lower
  "Pure layer of the raster backend, second stage: a plan's expressions lowered
  to the vocabulary `raster.core/deftm` compiles, one scalar kernel source per
  output component. Kernels take `Double` arguments, state first and then
  parameters, and return a `Double`.

  Nothing here evaluates a form; [[emmy.viewer.raster]] defines and compiles the
  kernels.")

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

(defn- unsupported!
  "Refuses `x`. emmy-viewers signals build-time errors by throwing, so this
  backend does too; the message names the form and the way out."
  [x]
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
