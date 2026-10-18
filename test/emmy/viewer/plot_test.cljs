(ns emmy.viewer.plot-test
  (:require [clojure.test :refer [is deftest testing]]
            [emmy.viewer.plot :as plot]))

(deftest format-tests
  (testing "format-number"
    (is (= "123" (plot/format-number 123)))
    (is (= "123.10" (plot/format-number 123.1))
        "Any decimal part triggers the default precision of 2.")

    (is (= "123" (plot/format-number 123.001))
        "If the precision leaves nothing after the decimal point, prints as an
        integer.")

    (is (= "123.11" (plot/format-number 123.11)))
    (is (= "123.11" (plot/format-number 123.111))
        "decimals are truncated to 2 by default")

    (is (= "123.667" (plot/format-number 123.6666 3))
        "Supplying `n` rounds the input number to that many decimal places."))

  (testing "label-pi"
    (is (= "0"      (plot/label-pi 0)))
    (is (= "0"      (plot/label-pi -0)))
    (is (= "π"      (plot/label-pi Math/PI)))
    (is (= "-π"     (plot/label-pi (- Math/PI))))
    (is (= "1.34π"  (plot/label-pi (* 1.34123 Math/PI) 2)))
    (is (= "1.30π"  (plot/label-pi (* 1.3 Math/PI) 2)))
    (is (= "π"      (plot/label-pi (* 1.001 Math/PI) 2)))
    (is (= "1.001π" (plot/label-pi (* 1.001 Math/PI) 3)))))

(deftest mathbox-format-arity-tests
  ;; MathBox's Format primitive calls its `:expr` as `expr(x, y, z, w, i, j,
  ;; k, l)`. A multi-arity CLJS fn without a variadic arity throws
  ;; "Invalid arity: 8" when called like that from JS.
  (testing "format-number ignores MathBox's extra arguments"
    (is (= "123.10" (plot/format-number 123.1 0 0 0 0 0 0 0)))
    (is (= "123.10" (.call plot/format-number nil 123.1 0 0 0 0 0 0 0))
        "called the way MathBox calls it, from JS with 8 arguments")
    (is (= "123" (plot/format-number 123 1 2)))
    (is (= "2" (plot/format-number 2.0 nil nil nil)))
    (is (= "123.67" (plot/format-number 123.6666 0 0 0 4 5 6 7))
        "precision stays at the default 2; the second argument is not `n`"))

  (testing "label-pi ignores MathBox's extra arguments"
    (is (= "π" (plot/label-pi Math/PI 0 0 0 0 0 0 0)))
    (is (= "π" (.call plot/label-pi nil Math/PI 0 0 0 0 0 0 0)))
    (is (= (plot/label-pi (/ Math/PI 2))
           (plot/label-pi (/ Math/PI 2) 1 2 3 4 5 6 7))
        "default precision; the second argument is not `precision`"))

  (testing "1- and 2-arity output is unchanged"
    (is (= "123.10" (plot/format-number 123.1)))
    (is (= "123.667" (plot/format-number 123.6666 3)))
    (is (= "1.34π" (plot/label-pi (* 1.34123 Math/PI) 2)))))
