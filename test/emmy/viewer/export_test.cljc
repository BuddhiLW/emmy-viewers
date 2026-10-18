(ns emmy.viewer.export-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [emmy.env :as e]
            [emmy.jsxgraph :as jsx]
            [emmy.leva :as leva]
            [emmy.mafs :as mafs]
            [emmy.mathbox :as box]
            [emmy.mathbox.plot :as plot]
            [emmy.viewer :as ev]
            [emmy.viewer.export :as x]))

;; `=` treats a list and a vector with the same elements as equal, and Reagent
;; does not, so round trips are also checked with [[same-shape?]].

(defn- kind [v]
  (cond (map? v) :map (set? v) :set (vector? v) :vector
        (seq? v) :list :else (type v)))

(defn- same-shape?
  "True if `a` and `b` are equal AND every nested collection has the same kind
  (list, vector, map, set) and every leaf the same type."
  [a b]
  (and (= (kind a) (kind b))
       (case (kind a)
         (:vector :list) (and (= (count a) (count b))
                              (every? true? (map same-shape? a b)))
         :map (and (= (set (keys a)) (set (keys b)))
                   (every? (fn [k] (same-shape? (get a k) (get b k)))
                           (keys a)))
         :set (= a b)
         (= a b))))

(defn- expanded
  "The value that renders: the fragment after [[emmy.viewer/expand]], with
  its metadata, so render-time transforms are compared as well."
  [frag]
  (let [v (ev/expand frag)]
    [v (meta v)]))

(defn- check-round-trip [frag]
  (doseq [[label write read] [["EDN" x/fragment->edn x/edn->fragment]
                              ["JSON" x/fragment->json x/json->fragment]]]
    (testing label
      (let [s  (write frag)
            rt (read s)]
        (is (string? s))
        (is (= frag rt) "same form")
        (is (same-shape? frag rt) "same collection kinds and leaf types")
        (is (= (meta frag) (meta rt)) "same metadata, viewer included")
        (is (= (expanded frag) (expanded rt))
            "emmy.viewer/expand gives the same value")
        (is (same-shape? (ev/expand frag) (ev/expand rt)))
        (is (= s (write rt)) "writing the read-back fragment is a fixed point")))))

;; ## Samples built with the library's own constructors

(def mafs-sample
  (mafs/mafs
   {:view-box {:x [-3 3] :y [-2 2]}}
   (mafs/cartesian)
   (mafs/of-x {:y e/sin :color :blue})
   (mafs/point [1 2] {:color :red})
   (ev/with-let [!xy [0.5 1.0]]
     (mafs/movable-point {:atom !xy
                          :constrain '(fn [[x _]] [x (Math/sin x)])}))))

(def mafs-child-sample
  ;; A standalone child: its viewer is emmy.mafs/default-viewer, a transform
  ;; that wraps it in a Mafs scene at render time.
  (mafs/of-x {:y '(fn [x] (* x x)) :color :green}))

(def mathbox-sample
  (box/mathbox
   {:container {:style {:height "400px" :width "100%"}}
    :focus 3}
   (box/camera {:position [1 1 2] :proxy true})
   (box/cartesian {:range [[-2 2] [-1 1] [-1 1]] :scale [2 1 1]}
                  (box/axis {:axis 1 :width 3})
                  (box/grid {:divide-x 10 :divide-y 5}))))

(def mathbox-plot-sample
  ;; Viewer is emmy.mathbox.plot/scene, another transform.
  (plot/point {:coords [1 2 3] :label "p" :color "#ff00ff" :size 1.5}))

(def jsxgraph-sample
  (jsx/jsxgraph
   {:boundingbox [-5 5 5 -5] :axis true :showCopyright false}
   (jsx/point {:parents [1 2] :name "A" :size 3})
   (jsx/point {:parents [-1 -2] :name "B"})
   (jsx/line {:parents ["A" "B"] :strokeColor "#00ff00"})))

(def leva-sample
  (ev/with-let [!state {:x 1.0 :n 2 :label "hi" :on? true}]
    [:<>
     (leva/controls
      {:atom !state
       :folder {:name "params"}
       :schema {:x {:min 0 :max 10 :step 0.5}
                :n {:value 2 :min 1 :max 5 :step 1}}})
     [:pre (ev/get !state :x)]]))

(deftest round-trip-tests
  (testing "mafs" (check-round-trip mafs-sample))
  (testing "mafs standalone child" (check-round-trip mafs-child-sample))
  (testing "mathbox" (check-round-trip mathbox-sample))
  (testing "mathbox plot child" (check-round-trip mathbox-plot-sample))
  (testing "jsxgraph" (check-round-trip jsxgraph-sample))
  (testing "leva" (check-round-trip leva-sample)))

(deftest viewer-restoration-tests
  (testing "transform viewers come back as the very same functions"
    (is (identical? mafs/default-viewer
                    (-> mafs-child-sample x/fragment->edn x/edn->fragment
                        meta :nextjournal.clerk/viewer)))
    (is (identical? plot/scene
                    (-> mathbox-plot-sample x/fragment->json x/json->fragment
                        meta :nextjournal.clerk/viewer))))

  (testing "the viewer is written as a symbol"
    (is (str/includes? (x/fragment->edn mafs-child-sample)
                       ":nextjournal.clerk/viewer emmy.mafs/default-viewer"))
    (is (str/includes? (x/fragment->json mafs-child-sample)
                       "{\"~sym\":\"emmy.mafs/default-viewer\"}")))

  (testing "expand runs the restored transform"
    (let [rt (-> mafs-child-sample x/fragment->json x/json->fragment)]
      (is (= 'mafs.core/Mafs (first (ev/expand rt))))
      (is (= (ev/expand mafs-child-sample) (ev/expand rt))))))

(deftest canonical-output-tests
  (testing "map insertion order does not change the output"
    (let [a (ev/fragment [:div (array-map :b 1 :a 2 :c {:z 1 :y 2})])
          b (ev/fragment [:div (array-map :c (array-map :y 2 :z 1) :a 2 :b 1)])]
      (is (= (x/fragment->edn a) (x/fragment->edn b)))
      (is (= (x/fragment->json a) (x/fragment->json b)))))

  (testing "sets are written in a fixed order"
    (is (= (x/fragment->edn (ev/fragment [:div #{:c :a :b}]))
           (x/fragment->edn (ev/fragment [:div (hash-set :b :a :c)]))))))

(deftest json-encoding-tests
  ;; Ratios exist only on the JVM: a `1/2` literal is not a valid
  ;; ClojureScript constant, so the cljs build uses a double there.
  (let [frag (ev/fragment
              (list 'reagent.core/with-let ['a (list 'reagent.core/atom 1)]
                    [:div {:k "v" "s" 'sym :d 1.0 :n nil}
                     '(fn [x] x) #{:x} "~kw" #?(:clj 1/2 :cljs 0.5) \c 3 2.5]))
        s    (x/fragment->json frag)]
    (testing "explicit tags"
      (is (str/includes? s "{\"~list\":[{\"~sym\":\"reagent.core/with-let\"}"))
      (is (str/includes? s "[{\"~kw\":\"div\"},{\"~map\":"))
      (is (str/includes? s "{\"~set\":[{\"~kw\":\"x\"}]}"))
      #?(:clj (is (str/includes? s "{\"~ratio\":\"1/2\"}")))
      #?(:clj (is (str/includes? s "{\"~char\":\"c\"}"))))
    (testing "strings that look like tags stay strings"
      (is (str/includes? s #?(:clj  "\"~kw\",{\"~ratio\""
                              :cljs "\"~kw\",0.5"))))
    (testing "lossless"
      (let [rt   (x/json->fragment s)
            body (nth rt 2)]
        (is (= frag rt))
        (is (same-shape? frag rt))
        (is (= #?(:clj 1/2 :cljs 0.5) (nth body 5)))
        (is (= \c (nth body 6)))
        (is (double? (get-in body [1 :d])))
        (is (= 1.0 (get-in body [1 :d])))
        (is (nil? (get-in body [1 :n])))
        (is (contains? (nth body 1) :n))))
    (testing "the JSON document carries format and version"
      (is (str/starts-with? s "{\"format\":\"emmy-viewers/fragment\",\"version\":1,")))))

(deftest error-tests
  (testing "values that are not data are refused"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (x/fragment->edn (ev/fragment [:div (atom 1)])))))
  (testing "a value without viewer metadata is not a fragment"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (x/fragment->edn [:div]))))
  (testing "unregistered viewers are refused, then accepted once registered"
    (let [xform (fn [v] (ev/fragment [:section v]))
          frag  (ev/fragment [:p "x"] xform)]
      (is (thrown? #?(:clj Exception :cljs js/Error)
                   (x/fragment->edn frag)))
      (x/register-viewer! 'emmy.viewer.export-test/section (fn [] xform))
      (let [rt (-> frag x/fragment->edn x/edn->fragment)]
        (is (identical? xform (:nextjournal.clerk/viewer (meta rt))))
        (is (= (ev/expand frag) (ev/expand rt))))))
  (testing "unknown viewer symbols are refused on read"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (x/edn->fragment
                  (str/replace (x/fragment->edn mafs-child-sample)
                               "emmy.mafs/default-viewer"
                               "no.such/viewer"))))))
