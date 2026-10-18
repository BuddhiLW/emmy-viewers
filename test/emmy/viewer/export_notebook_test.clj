(ns emmy.viewer.export-notebook-test
  "Runs [[emmy.viewer.export/export-notebook!]] on a tiny notebook. The test
  passes `:custom-js`, so no viewer bundle is compiled (no shadow-cljs, no
  npm) and nothing is fetched: it asserts on the files Clerk writes, offline.
  The page itself is checked in a browser by `dev/e2e/export_render.mjs`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [emmy.viewer.export :as x]))

(def ^:private notebook-src
  "(ns export-notebook-fixture
  (:require [emmy.clerk :as ec]
            [emmy.env :as e]
            [emmy.mafs :as mafs]))

;; # Export fixture heading

(ec/install!)

(mafs/mafs (mafs/cartesian) (mafs/of-x e/sin))
")

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "emmy-export-notebook"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest export-notebook-test
  (let [dir      (temp-dir)
        notebook (io/file dir "fixture.clj")
        out-dir  (io/file dir "out")
        bundle   "https://example.invalid/emmy-viewers/main.js"]
    (spit notebook notebook-src)
    (let [ret (x/export-notebook! {:notebook  (str notebook)
                                   :out-dir   (str out-dir)
                                   :custom-js bundle})
          index-html (io/file out-dir "index.html")
          index-edn  (io/file out-dir "index.edn")]
      (testing "returns the paths, index-html pointing at the written page"
        (is (= (str notebook) (:notebook ret)))
        (is (= (str out-dir) (:out-dir ret)))
        (is (= (str index-html) (:index-html ret))))
      (testing "index.html loads the viewer bundle"
        (is (.isFile index-html))
        (let [html (slurp index-html)]
          (is (str/includes? html (str "src=\"" bundle "\"")))
          (is (str/includes? html "<div id=\"clerk\">"))))
      (testing "the notebook content is in index.edn"
        (is (.isFile index-edn))
        (let [edn (slurp index-edn)]
          (is (str/includes? edn "Export fixture heading"))
          ;; the Mafs fragment, as the Reagent form the viewer renders
          (is (str/includes? edn "mafs.core/Mafs")))))))

(deftest export-notebook-errors-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs :notebook and :out-dir"
                        (x/export-notebook! {:notebook "x.clj"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Notebook not found"
                        (x/export-notebook! {:notebook "no/such/notebook.clj"
                                             :out-dir  "target/never"}))))
