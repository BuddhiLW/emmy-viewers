(ns emmy.viewer.raster-test
  "The raster backend end to end: the form a viewer binds is evaluated in node,
  so the wasm modules run in V8, and every call is compared with the same
  function compiled by Emmy's `:js` mode.

  Needs raster on the classpath (`clojure -M:test:raster:runner`) and `node` on
  the path; each missing piece skips the suite with a message."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.test :refer [deftest is testing]]
            [emmy.env :as e]
            [emmy.mechanics.lagrange :as l]
            [emmy.viewer.compile :as vc]
            [emmy.viewer.raster.kernel :as k]))

(def ^:private raster?
  (try (require 'emmy.viewer.raster)
       true
       (catch Exception ex
         (if (re-find #"raster" (str (ex-message ex) (some-> ex ex-cause ex-message)))
           (do (println "[raster-test] raster not on the classpath (use -M:test:raster); skipping")
               false)
           (throw ex)))))

(def ^:private node?
  (let [ok (try (zero? (:exit (sh/sh "node" "--version")))
                (catch java.io.IOException _ false))]
    (when-not ok (println "[raster-test] node not on the path; skipping"))
    ok))

(defn- run-node
  "Evaluates the bound `form` and its `:js` fallback in node on every argument
  list in `cases`, and returns `{:wasm loaded? :raster [...] :js [...]}`.
  A :primitive function returns the array it wrote, of length `n-out`."
  [form convention n-out cases]
  (let [[[_ _ glue] [_ & fb]] form
        program (str "const fb = new Function(" (json/write-str (vec (butlast fb)))
                     ".join(','), " (json/write-str (last fb)) ");\n"
                     "const f = new Function('fb', " (json/write-str glue) ")(fb);\n"
                     "const run = (g, args) => {\n"
                     (if (= convention :primitive)
                       (str "  const yps = new Array(" n-out ").fill(0);\n"
                            "  g(args[0], yps, args[1]);\n"
                            "  return yps;\n")
                       "  return g(...args);\n")
                     "};\n"
                     "const cases = " (json/write-str cases) ";\n"
                     "console.log(JSON.stringify({wasm: f.wasm(),"
                     " raster: cases.map((c) => run(f, c)),"
                     " js: cases.map((c) => run(fb, c))}));\n")
        file    (java.io.File/createTempFile "raster-backend" ".js")]
    (spit file program)
    (let [{:keys [exit out err]} (sh/sh "node" (str file))]
      (io/delete-file file true)
      (if (zero? exit)
        (json/read-str out :key-fn keyword)
        (throw (ex-info (str "node failed: " err) {:program program}))))))

(defn- close?
  "Equal up to raster's wasm libm, which approximates the transcendental
  functions polynomially: sin/cos within 5e-6 and atan/asin/acos within 3e-5 of
  java.lang.Math, by raster's own measurement."
  [a b]
  (if (and (number? a) (number? b))
    (<= (Math/abs (- (double a) (double b)))
        (* 1e-4 (max 1.0 (Math/abs (double a)) (Math/abs (double b)))))
    (and (= (count a) (count b))
         (every? true? (map close? a b)))))

(defn- check
  "Compiles `f` with the raster backend, runs it on `cases` in node, and checks
  that the wasm kernels loaded and agree with the `:js` function, or with
  `expected` (a fn of the case, evaluated by Emmy on the JVM) when given."
  ([f params initial-state opts cases]
   (check f params initial-state opts cases nil))
  ([f params initial-state opts cases expected]
   (let [form  (binding [vc/*backend* :raster]
                 (vc/compiled-fn f params initial-state opts))
         conv  (:calling-convention opts :structure)
         n-out (count (:outputs (k/plan f params initial-state opts)))
         {:keys [wasm raster js]} (run-node form conv n-out cases)]
     (is (true? wasm) "the kernels loaded synchronously")
     (doseq [[c r j] (map vector cases raster js)
             :let [want (if expected (expected c) j)]]
       (is (close? r want) (str "case " (pr-str c) ": raster " r ", expected " want))))))

(deftest raster-backend-test
  (when (and raster? node?)
    (testing "native, one argument, as compile-1d emits it"
      (check (fn [x] (e/* x (e/sin x))) false [0]
             {:calling-convention :native :arity 1}
             [[0.0] [1.0] [-2.5] [3.7]]))

    (testing "an odd integer power of a negative base"
      (check (fn [x] (e/- (e/expt x 3) (e/* 2 x))) false [0]
             {:calling-convention :native :arity 1}
             [[-2.0] [-0.5] [1.5]]))

    (testing "a parametric curve returns one value per component"
      (check (fn [t] (e/up (e/cos t) (e/sin (e/* 2 t)))) false [0]
             {:calling-convention :native :arity 1}
             [[0.0] [0.7] [2.0]]))

    (testing "parameters, as param-1d emits them"
      (check (fn [a b] (fn [[x]] (e/+ (e/* a (e/sin (e/* b x))) (e/square x))))
             '[a b] [0] {}
             [[[0.5] [1.0 2.0]] [[-1.2] [0.3 -1.0]]]))

    (testing "two-argument atan is atan2 in every quadrant"
      ;; Checked against Emmy on the JVM: Emmy's :js mode renders (atan y x)
      ;; as Math.atan(y, x), which drops x, so it is wrong for x < 0.
      (check (fn [[x y]] (e/atan y x)) false [0 0] {}
             [[[1.0 1.0]] [[-1.0 0.5]] [[-1.0 -0.5]]]
             (fn [[[x y]]] (e/atan y x))))

    (testing ":primitive, as MathBox's compile-3d emits it"
      (check (fn [[u v]] (e/up u v (e/* (e/sin u) (e/cos v)))) false [0 0]
             {:calling-convention :primitive :generic-params? false}
             [[[0.3 0.4] nil] [[-1.0 2.0] nil]]))

    (testing ":primitive with params and a structured state, as physics' ode-compile emits it"
      (check (fn [k]
               (fn [[s]] ((l/Lagrangian->state-derivative (l/L-harmonic 1 k)) s)))
             '[k] [(e/up 0 0 0)]
             {:calling-convention :primitive :generic-params? true :simplify? false}
             [[[0.0 1.0 0.5] [2.0]] [[1.0 -0.3 0.2] [5.0]]]))))
