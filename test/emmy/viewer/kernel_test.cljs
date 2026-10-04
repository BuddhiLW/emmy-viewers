(ns emmy.viewer.kernel-test
  (:require [cljs.test :refer [deftest is testing]]
            [emmy.viewer.kernel :as kernel]))

(defn stub-kernel []
  (let [f (fn [state ps] (+ (first state) (first ps)))]
    (set! (.-dims f) #js {:state 1 :outputs 1 :params 1})
    (set! (.-ready f) (fn [] true))
    (set! (.-batch f)
          (fn [xs n ps out]
            (let [out (or out (js/Float64Array. n))]
              (dotimes [i n]
                (aset out i (+ (aget xs i) (first ps))))
              out)))
    f))

(deftest kernel-contract-test
  (let [k (stub-kernel)
        xs (js/Float64Array. #js [1 2 3])
        out (js/Float64Array. 3)]
    (is (kernel/kernel? k))
    (is (not (kernel/kernel? (fn [s _] s))))
    (is (not (kernel/kernel? nil)))
    (is (kernel/ready? k))
    (is (= {:state 1 :outputs 1 :params 1} (kernel/dims k)))
    (is (identical? out (kernel/batch! k xs 3 [10] out)))
    (is (= [11 12 13] (vec (array-seq out))))
    (is (= [11 12 13] (vec (array-seq (kernel/batch! k xs 3 [10] nil)))))))

(deftest bound-kernel-test
  (let [k (stub-kernel)
        xs (js/Float64Array. #js [1 2 3])]
    (doseq [[label g point] [["structured" (kernel/bind k [10]) [2]]
                             ["scalar" (kernel/bind-1d k [10]) 2]]]
      (testing label
        (is (fn? g))
        (is (= 12 (g point)))
        (is (kernel/kernel? g))
        (is (kernel/ready? g))
        (is (= (kernel/dims k) (kernel/dims g)))
        (is (= [11 12 13]
               (vec (array-seq (kernel/batch! g xs 3 [:ignored] nil)))))
        (let [out (js/Float64Array. 3)]
          (is (identical? out (kernel/batch! g xs 3 nil out))))))))

(deftest bound-plain-function-test
  (let [f (fn [state ps] (+ (first state) (first ps)))
        g (kernel/bind f [10])
        scalar (kernel/bind-1d f [10])]
    (is (= 12 (g [2])))
    (is (= 12 (scalar 2)))
    (is (not (kernel/kernel? g)))
    (is (not (kernel/kernel? scalar)))))
