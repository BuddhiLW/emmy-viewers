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
            [emmy.viewer.raster.plan :as plan]))

(defn- skip!
  "Reports a missing prerequisite. A skip, unless EMMY_VIEWERS_REQUIRE_RASTER
  is set (the raster CI job), where a skipped suite would read as green."
  [msg]
  (if (System/getenv "EMMY_VIEWERS_REQUIRE_RASTER")
    (throw (ex-info (str msg " (EMMY_VIEWERS_REQUIRE_RASTER is set)") {}))
    (println (str "[raster-test] " msg "; skipping"))))

(def ^:private raster?
  (try (require 'emmy.viewer.raster)
       true
       (catch Exception ex
         (if (re-find #"raster" (str (ex-message ex) (some-> ex ex-cause ex-message)))
           (do (skip! "raster not on the classpath (use -M:test:raster)")
               false)
           (throw ex)))))

(def ^:private node?
  (let [ok (try (zero? (:exit (sh/sh "node" "--version")))
                (catch java.io.IOException _node-not-installed false))]
    (when-not ok (skip! "node not on the path"))
    ok))

(defn- run-node
  "Evaluates the bound `form` and its `:js` fallback in node on every argument
  list in `cases`, and returns `{:wasm loaded? :raster [...] :js [...] :batch
  [...]}`. A :primitive function returns the array it wrote, of length `n-out`;
  `:batch` holds each case evaluated through `f.batch`, flattened."
  [form convention n-out cases]
  (let [[[_ _ glue] [_ & fb]] form
        program (str "const fb = new Function(" (json/write-str (vec (butlast fb)))
                     ".join(','), " (json/write-str (last fb)) ");\n"
                     "const f = new Function('fb', " (json/write-str glue) ")(fb);\n"
                     "const flat = (v, o = []) => { if (typeof v === 'number') o.push(v); else if (v != null) for (const x of v) flat(x, o); return o; };\n"
                     "const run = (g, args) => {\n"
                     (if (= convention :primitive)
                       (str "  const yps = new Array(" n-out ").fill(0);\n"
                            "  g(args[0], yps, args[1]);\n"
                            "  return yps;\n")
                       "  return g(...args);\n")
                     "};\n"
                     "const batch = (args) => {\n"
                     (if (= convention :native)
                       "  return Array.from(f.batch(flat(args), 1, null));\n"
                       "  return Array.from(f.batch(flat(args[0]), 1, args[1]));\n")
                     "};\n"
                     "const cases = " (json/write-str cases) ";\n"
                     "console.log(JSON.stringify({wasm: f.wasm(),"
                     " raster: cases.map((c) => run(f, c)),"
                     " js: cases.map((c) => run(fb, c)),"
                     " batch: cases.map(batch)}));\n")
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
  that the wasm kernel loaded, that it agrees with the `:js` function (or with
  `expected`, a fn of the case evaluated by Emmy on the JVM, when given), and
  that `f.batch` agrees with the per-point calls."
  ([f params initial-state opts cases]
   (check f params initial-state opts cases nil))
  ([f params initial-state opts cases expected]
   (let [form  (binding [vc/*backend* :raster]
                 (vc/compiled-fn f params initial-state opts))
         conv  (:calling-convention opts :structure)
         n-out (count (:outputs (plan/plan f params initial-state opts)))
         {:keys [wasm raster js batch]} (run-node form conv n-out cases)]
     (is (true? wasm) "the kernel loaded synchronously")
     (doseq [[c r j b] (map vector cases raster js batch)
             :let [want (if expected (expected c) j)]]
       (is (close? r want) (str "case " (pr-str c) ": raster " r ", expected " want))
       (is (close? (flatten (if (number? r) [r] r)) b)
           (str "case " (pr-str c) ": batch " b ", per point " r))))))

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
