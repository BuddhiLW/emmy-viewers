(ns emmy.viewer.raster
  "The raster computational backend: Emmy functions compiled to WebAssembly by
  [raster](https://github.com/replikativ/raster) instead of to JavaScript source.

  Emmy still does the algebra. The function is simplified symbolically, each
  output component becomes a `raster.core/deftm` kernel, and raster compiles each
  kernel to an import-free wasm module that ships inside the viewer fragment as
  base64. The browser side keeps the calling convention of Emmy's `:js` mode, and
  falls back to the `:js` function while a module is loading or when the browser
  has no WebAssembly.

  Select it around the code that builds a viewer:

  ```clojure
  (require '[emmy.viewer.compile :as vc])

  (binding [vc/*backend* :raster]
    (emmy.mafs/of-x (fn [x] (* x (sin x)))))
  ```

  Requires raster on the classpath (the `:raster` alias). A function raster cannot
  compile (an operator outside [[emmy.viewer.raster.kernel/lower]]'s vocabulary)
  throws at build time, naming the form."
  (:require [emmy.expression.compile :as xc]
            [emmy.viewer.raster.kernel :as k]
            [raster.compiler.pipeline :as pl]
            [raster.core]
            [raster.math]
            [raster.numeric])
  (:import (java.util Base64)))

(def ^:private kernel-ns
  "Where kernels are defined. raster resolves a kernel through its var."
  'emmy.viewer.raster.kernels)

(defn- ensure-kernel-ns! []
  (or (find-ns kernel-ns)
      (let [n (create-ns kernel-ns)]
        (binding [*ns* n]
          (refer-clojure)
          (require '[raster.core] '[raster.math] '[raster.numeric]))
        n)))

(defn- wasm-bytes*
  "Defines the kernel `form` and compiles it to a wasm module exporting `k`."
  [form]
  (let [n     (ensure-kernel-ns!)
        kname (second form)]
    (binding [*ns* n]
      (eval form))
    (-> (pl/compile-wasm (ns-resolve n kname) :name "k" :dtype :double)
        :bytes
        byte-array)))

(def ^:private wasm-bytes
  "Kernel source to module bytes. Memoized on the source with the kernel name
  left out, so an expression compiles once however many viewers use it."
  (let [cache (atom {})]
    (fn [form]
      (let [key (drop 2 form)]
        (or (@cache key)
            (let [bs (wasm-bytes* form)]
              (swap! cache assoc key bs)
              bs))))))

(defn modules
  "The wasm modules, one per output of `plan`, as byte arrays."
  [plan]
  (mapv wasm-bytes (k/kernel-forms plan (fn [_] (gensym "kernel")))))

(defn- base64 [^bytes bs]
  (.encodeToString (Base64/getEncoder) bs))

(defn compiled-fn
  "The raster implementation of [[emmy.viewer.compile/compiled-fn]]: a form that
  evaluates, in the browser, to the compiled function."
  [f params initial-state opts]
  (let [plan     (k/plan f params initial-state opts)
        mods     (modules plan)
        sync?    (every? #(<= (alength ^bytes %) k/sync-limit) mods)
        fallback (xc/compile-state-fn f params initial-state (assoc opts :mode :js))]
    (list (list 'js/Function. "fb" (k/glue plan (mapv base64 mods) sync?))
          (list* 'js/Function. fallback))))
