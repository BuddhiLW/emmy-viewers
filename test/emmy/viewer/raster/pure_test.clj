(ns emmy.viewer.raster.pure-test
  "The raster backend's pure layers (plan, lower, glue) and the :js parity of
  the backend seam. Needs neither raster nor malli."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [emmy.env :as e]
            [emmy.expression.compile :as xc]
            [emmy.mechanics.lagrange :as l]
            [emmy.viewer.compile :as vc]
            [emmy.viewer.raster.glue :as glue]
            [emmy.viewer.raster.lower :as lower]
            [emmy.viewer.raster.plan :as plan]))

(deftest lower-test
  (testing "integer powers become products, so a negative base stays defined"
    (is (= '(* (* x x) x) (lower/lower '[x] '(expt x 3))))
    (is (= '(/ 1.0 (* x x)) (lower/lower '[x] '(expt x -2))))
    (is (= 1.0 (lower/lower '[x] '(expt x 0))))
    (is (= '(* x x) (lower/lower '[x] '(expt x 2.0)))))

  (testing "a fractional power goes to raster's pow"
    (is (= '(raster.numeric/pow x 0.5) (lower/lower '[x] '(expt x 1/2)))))

  (testing "variadic + and * nest into binary calls, literals become doubles"
    (is (= '(+ (+ 1.0 x) y) (lower/lower '[x y] '(+ 1 x y))))
    (is (= '(* 0.5 x) (lower/lower '[x] '(* 1/2 x))))
    (is (= '(- 0.0 x) (lower/lower '[x] '(- x)))))

  (testing "functions map to raster's, two-argument atan to atan2"
    (is (= '(raster.math/sin (raster.math/cos x))
           (lower/lower '[x] '(sin (cos x)))))
    (is (= '(raster.math/atan2 y x) (lower/lower '[x y] '(atan y x))))
    (is (= '(Math/sqrt x) (lower/lower '[x] '(sqrt x)))))

  (testing "what raster cannot compile is refused, naming the form"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot compile \(f x\)"
                          (lower/lower '[x] '(f x))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot compile z"
                          (lower/lower '[x] '(+ x z))))))

(deftest shape-test
  (is (nil? (plan/shape 'x)))
  (is (= [0 1] (plan/shape (e/up 'a 'b))))
  (is (= [[0 1] 2] (plan/shape (e/up (e/up 'a 'b) 'c))))
  (is (= '[a b c] (plan/leaves (e/up (e/up 'a 'b) 'c)))))

(deftest plan-test
  (testing "native calling convention, one argument"
    (let [p (plan/plan (fn [x] (e/* x (e/sin x))) false [0]
                       {:calling-convention :native})]
      (is (= :native (:convention p)))
      (is (= '[s0] (:state p)))
      (is (= [] (:params p)))
      (is (= '[(* s0 (sin s0))] (:outputs p)))
      (is (nil? (:shape p)))))

  (testing "a vector output becomes one output per component"
    (let [p (plan/plan (fn [t] (e/up (e/cos t) (e/sin t))) false [0]
                       {:calling-convention :native})]
      (is (= '[(cos s0) (sin s0)] (:outputs p)))
      (is (= [0 1] (:shape p)))))

  (testing "generic params come after the state"
    (let [p (plan/plan (fn [a] (fn [[x y]] (e/* a x y))) '[a] [0 0] {})]
      (is (= :structure (:convention p)))
      (is (= '[s0 s1] (:state p)))
      (is (= '[p0] (:params p)))
      (is (= '[(* p0 s0 s1)] (:outputs p)))))

  (testing "a structured state flattens depth first"
    (let [p (plan/plan (l/Lagrangian->state-derivative (l/L-harmonic 1 2))
                       false (e/up 0 0 0)
                       {:calling-convention :primitive :generic-params? false})]
      (is (= '[s0 s1 s2] (:state p)))
      (is (= '[1 s2 (* -2 s1)] (:outputs p))))))

(deftest kernel-forms-test
  (let [p (plan/plan (fn [a] (fn [[x]] (e/* a (e/expt x 2)))) '[a] [0] {})]
    (is (= '[(raster.core/deftm k0 [s0 :- Double p0 :- Double] :- Double
               (* p0 (* s0 s0)))]
           (lower/kernel-forms p (fn [i] (symbol (str "k" i))))))))

(deftest glue-test
  (let [p {:convention :primitive :state '[s0 s1] :params '[p0]
           :outputs '[a b c] :shape [0 1 2]}]
    (testing "small modules load synchronously, large ones asynchronously"
      (is (str/includes? (glue/glue p ["AA=="] true) "new WebAssembly.Module"))
      (is (str/includes? (glue/glue p ["AA=="] false) "WebAssembly.compile")))
    (testing "a :primitive function writes one slot per output"
      (let [src (glue/glue p ["AA==" "AA==" "AA=="] true)]
        (is (str/includes? src "yps[2] = ks[2](...a);"))
        (is (str/includes? src ".concat(flat(ps, []))")))))
  (testing "a native function takes its arguments positionally"
    (let [src (glue/glue {:convention :native :state '[s0] :params []
                          :outputs '[a b] :shape [0 1]}
                         ["AA==" "AA=="] true)]
      (is (str/includes? src "function(s0)"))
      (is (str/includes? src "return [ks[0](s0), ks[1](s0)];")))))

(deftest backend-test
  (let [f (fn [x] (e/* x (e/sin x)))]
    (testing ":js is the default and emits Emmy's js/Function call"
      (is (= :js vc/*backend*))
      (is (= (list* 'js/Function.
                    (xc/compile-state-fn f false [0] {:calling-convention :native
                                                      :arity 1
                                                      :mode :js}))
             (vc/compiled-fn f false [0] {:calling-convention :native
                                          :arity 1}))))
    (testing "an unknown backend is refused"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown compile backend :gpu"
                            (binding [vc/*backend* :gpu]
                              (vc/compiled-fn f false [0] {})))))))
