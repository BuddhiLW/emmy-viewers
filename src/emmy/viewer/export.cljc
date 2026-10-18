(ns emmy.viewer.export
  "Serialization of Emmy-Viewers fragments, so that a figure built on the server
  can be stored, shipped and rendered again later.

  A fragment (see [[emmy.viewer/fragment]]) is a quoted Reagent form plus
  metadata that tells Clerk and Portal how to render it. The form is plain data,
  but the metadata is not: its `:nextjournal.clerk/viewer` entry holds a Clerk
  viewer map or a transform function (like [[emmy.mafs/default-viewer]]). This
  namespace writes that entry as the qualified SYMBOL of a registered viewer
  and resolves it again on read, so that [[emmy.viewer/expand]] and Clerk see
  the same value they would have seen on the original fragment.

  - [[fragment->edn]] / [[edn->fragment]]: canonical EDN text.
  - [[fragment->json]] / [[json->fragment]]: JSON for non-Clojure consumers,
    with an explicit encoding of symbols, keywords, lists, maps and sets (see
    the JSON section below).
  - [[register-viewer!]]: make your own viewer or transform exportable.
  - [[export-notebook!]] (Clojure only): static build of ONE Clerk notebook.

  Only the top-level metadata of a fragment is kept. Nested fragments lose
  theirs, which is what the renderers do anyway: Clerk's reagent viewer and
  Portal both strip nested viewer metadata before they evaluate the form."
  (:require [clojure.string :as str]
            [emmy.mafs :as mafs]
            [emmy.mathbox.plot :as plot]
            [emmy.viewer :as ev]
            #?(:clj [clojure.data.json :as json])
            #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])))

(def format-id
  "Identifier written into every exported fragment."
  "emmy-viewers/fragment")

(def version
  "Version of the export format written by this namespace."
  1)

(def ^:private viewer-key :nextjournal.clerk/viewer)

;; ## Viewer registry
;;
;; Each entry maps a qualified symbol to a no-argument function returning the
;; viewer's CURRENT value. The thunk matters for `emmy.viewer/reagent-viewer`,
;; which is `nil` until `emmy.clerk` is loaded and installs Clerk's viewer.

(defonce ^:private !viewers
  (atom {'emmy.viewer/reagent-viewer (fn [] ev/reagent-viewer)
         'emmy.mafs/default-viewer   (fn [] mafs/default-viewer)
         'emmy.mathbox.plot/scene    (fn [] plot/scene)}))

(defn register-viewer!
  "Registers `sym`, a qualified symbol, as the exported name of a viewer.
  `value-fn` is a no-argument function returning the viewer (a Clerk viewer map
  or a transform function). Fragments whose `:nextjournal.clerk/viewer` is
  identical to that value export as `sym`, and `sym` resolves back to it on
  read. Returns `sym`."
  [sym value-fn]
  (assert (qualified-symbol? sym) "viewer names must be qualified symbols")
  (swap! !viewers assoc sym value-fn)
  sym)

(defn registered-viewers
  "Returns the set of symbols that name exportable viewers."
  []
  (set (keys @!viewers)))

(defn- viewer->sym
  "Returns the registered symbol for `viewer`. A `nil` viewer is the default
  one, `emmy.viewer/reagent-viewer`, since [[emmy.viewer/fragment]] puts that in
  place of `nil`."
  [viewer]
  (if (nil? viewer)
    'emmy.viewer/reagent-viewer
    (or (some (fn [[sym value-fn]]
                (when (identical? viewer (value-fn)) sym))
              (sort-by str @!viewers))
        (throw
         (ex-info
          (str "Cannot export a fragment whose viewer is not registered. "
               "Name it with emmy.viewer.export/register-viewer!.")
          {:viewer viewer
           :registered (registered-viewers)})))))

(defn- sym->viewer [sym]
  (if-let [value-fn (get @!viewers sym)]
    (value-fn)
    (throw
     (ex-info (str "Unknown viewer " sym " in exported fragment.")
              {:viewer sym :registered (registered-viewers)}))))

;; ## Canonical data
;;
;; A fragment's form must be plain data. Maps and sets are rewritten into sorted
;; collections, so the same fragment always prints the same text whatever the
;; hash order of the original collections.

(defn- type-rank [x]
  (cond (nil? x) 0 (boolean? x) 1 (number? x) 2 (string? x) 3
        (keyword? x) 4 (symbol? x) 5 :else 6))

(defn- canonical-compare [a b]
  (let [c (compare (type-rank a) (type-rank b))]
    (if (zero? c)
      (compare (pr-str a) (pr-str b))
      c)))

(defn- data-error [x]
  (ex-info (str "Fragment contains a value that is not plain data: "
                (pr-str x))
           {:value x :type (type x)}))

(defn- canonical
  "Returns `x` with every map and set replaced by a sorted equivalent. Throws on
  any value that is not plain data (functions, atoms, records, regexes ...)."
  [x]
  (cond (or (nil? x) (boolean? x) (string? x) (keyword? x) (symbol? x)
            (number? x) (char? x))
        x

        (record? x)  (throw (data-error x))
        (map-entry? x) [(canonical (key x)) (canonical (val x))]
        (vector? x)  (mapv canonical x)
        (map? x)     (into (sorted-map-by canonical-compare)
                           (map (fn [[k v]] [(canonical k) (canonical v)]))
                           x)
        (set? x)     (into (sorted-set-by canonical-compare)
                           (map canonical)
                           x)
        (seq? x)     (apply list (map canonical x))
        :else        (throw (data-error x))))

(defn- split-meta
  "Returns the exportable metadata of `frag`: every top-level entry, with the
  viewer replaced by its registered symbol."
  [frag]
  (let [m (meta frag)]
    (when-not (contains? m viewer-key)
      (throw (ex-info "Not a fragment: no :nextjournal.clerk/viewer metadata."
                      {:value frag})))
    (canonical (assoc m viewer-key (viewer->sym (get m viewer-key))))))

(defn- restore-meta [form m]
  (with-meta form
    (assoc m viewer-key (sym->viewer (get m viewer-key)))))

(defn- envelope [frag]
  {:emmy.viewer.export/format  format-id
   :emmy.viewer.export/version version
   :emmy.viewer.export/form    (canonical frag)
   :emmy.viewer.export/meta    (split-meta frag)})

(defn- check-envelope [m]
  (when-not (and (map? m)
                 (= format-id (:emmy.viewer.export/format m)))
    (throw (ex-info "Not an exported Emmy-Viewers fragment." {:value m})))
  (when-not (= version (:emmy.viewer.export/version m))
    (throw (ex-info "Unsupported fragment export version."
                    {:version (:emmy.viewer.export/version m)
                     :supported version})))
  m)

;; ## EDN

(defn fragment->edn
  "Returns a canonical EDN string for the fragment `frag`: a map

  ```clojure
  {:emmy.viewer.export/form    <the quoted Reagent form>
   :emmy.viewer.export/format  \"emmy-viewers/fragment\"
   :emmy.viewer.export/meta    {... :nextjournal.clerk/viewer <symbol>}
   :emmy.viewer.export/version 1}
  ```

  Maps and sets are written in a fixed order, so equal fragments give
  byte-identical strings. Throws if `frag` is not a fragment, contains anything
  but plain data, or uses a viewer that is not registered (see
  [[register-viewer!]])."
  [frag]
  (binding [*print-length*         nil
            *print-level*          nil
            *print-meta*           false
            *print-namespace-maps* false]
    (pr-str (canonical (envelope frag)))))

(defn edn->fragment
  "Reads a string written by [[fragment->edn]] and returns the fragment, with its
  metadata restored, so that [[emmy.viewer/expand]], Clerk and Portal render it
  exactly as they render the original."
  [s]
  (let [m (check-envelope (edn/read-string s))]
    (restore-meta (:emmy.viewer.export/form m)
                  (:emmy.viewer.export/meta m))))

;; ## JSON
;;
;; Encoding of a Clojure value `x` as JSON, written `J(x)`:
;;
;; | Clojure value               | JSON                                   |
;; |-----------------------------|----------------------------------------|
;; | `nil`, `true`, `false`      | `null`, `true`, `false`                |
;; | string                      | string                                 |
;; | integer (fits in 64 bits)   | number without a fraction: `3`         |
;; | double, finite              | number; integral doubles keep `.0`     |
;; | `##Inf`, `##-Inf`, `##NaN`  | `{\"~double\": \"Infinity\"}` etc.     |
;; | ratio `1/2`                 | `{\"~ratio\": \"1/2\"}`                |
;; | big integer `1N`            | `{\"~bigint\": \"1\"}`                 |
;; | big decimal `1.5M`          | `{\"~bigdec\": \"1.5\"}`               |
;; | char `\\a`                  | `{\"~char\": \"a\"}`                   |
;; | keyword `:ns/k`             | `{\"~kw\": \"ns/k\"}`                  |
;; | symbol `ns/s`               | `{\"~sym\": \"ns/s\"}`                 |
;; | vector `[a b]`              | array `[J(a), J(b)]`                   |
;; | list or seq `(a b)`         | `{\"~list\": [J(a), J(b)]}`            |
;; | map `{k v}`                 | `{\"~map\": [[J(k), J(v)], ...]}`      |
;; | set `#{a}`                  | `{\"~set\": [J(a), ...]}`              |
;;
;; Every JSON object in the encoding has exactly one key, and that key is one
;; of the tags above; so strings never need escaping and a decoder can tell
;; every case apart. Map entries and set members are in the canonical order
;; used for EDN. The document is
;;
;; {\"format\": \"emmy-viewers/fragment\", \"version\": 1,
;;  \"form\": J(form), \"meta\": J(metadata)}
;;
;; with the viewer in the metadata written as a symbol. JavaScript has a single
;; number type, so a JS consumer sees `1.0` and `1` as the same number; Clojure
;; reads back `1.0` as a double and `1` as a long.

(defn- name-of [x]
  (if-let [ns (namespace x)]
    (str ns "/" (name x))
    (name x)))

(defn- tagged [tag v]
  #?(:clj  (array-map tag v)
     :cljs (js-obj tag v)))

(defn- json-array [xs]
  #?(:clj  (vec xs)
     :cljs (into-array xs)))

(defn- encode-number [x]
  #?(:clj
     (cond (ratio? x)                        (tagged "~ratio" (str x))
           (instance? clojure.lang.BigInt x) (tagged "~bigint" (str x))
           (instance? java.math.BigInteger x) (tagged "~bigint" (str x))
           (decimal? x)                      (tagged "~bigdec" (str x))
           (and (double? x) (Double/isNaN x)) (tagged "~double" "NaN")
           (and (double? x) (Double/isInfinite x))
           (tagged "~double" (if (pos? x) "Infinity" "-Infinity"))
           (float? x)                        (double x)
           :else                             (long x))
     :cljs
     (cond (js/isNaN x)          (tagged "~double" "NaN")
           (not (js/isFinite x)) (tagged "~double" (if (pos? x) "Infinity" "-Infinity"))
           :else                 x)))

(defn- encode
  "Returns the JSON-ready encoding J(x) of the canonical value `x`."
  [x]
  (cond (or (nil? x) (boolean? x) (string? x)) x
        (number? x)  (encode-number x)
        (char? x)    (tagged "~char" (str x))
        (keyword? x) (tagged "~kw" (name-of x))
        (symbol? x)  (tagged "~sym" (name-of x))
        (vector? x)  (json-array (map encode x))
        (seq? x)     (tagged "~list" (json-array (map encode x)))
        (map? x)     (tagged "~map" (json-array
                                     (map (fn [[k v]]
                                            (json-array [(encode k) (encode v)]))
                                          x)))
        (set? x)     (tagged "~set" (json-array (map encode x)))
        :else        (throw (data-error x))))

(defn- parse-double-tag [s]
  (case s
    "NaN"       ##NaN
    "Infinity"  ##Inf
    "-Infinity" ##-Inf
    (throw (ex-info "Bad ~double value." {:value s}))))

(defn- decode-tag [tag v decode]
  (case tag
    "~kw"     (keyword v)
    "~sym"    (symbol v)
    "~char"   (first v)
    "~double" (parse-double-tag v)
    "~list"   (apply list (map decode v))
    "~set"    (into #{} (map decode) v)
    "~map"    (into {} (map (fn [[k x]] [(decode k) (decode x)])) v)
    "~ratio"  #?(:clj  (clojure.lang.Numbers/divide
                        (bigint (first (str/split v #"/")))
                        (bigint (second (str/split v #"/"))))
                 :cljs (let [[n d] (str/split v #"/")]
                         (/ (js/parseFloat n) (js/parseFloat d))))
    "~bigint" #?(:clj (bigint v) :cljs (js/parseInt v 10))
    "~bigdec" #?(:clj (bigdec v) :cljs (js/parseFloat v))
    (throw (ex-info (str "Unknown JSON tag " tag) {:tag tag :value v}))))

(defn- decode
  "Inverse of [[encode]], over the data produced by the JSON reader (string
  keys, vectors for arrays)."
  [x]
  (cond (vector? x) (mapv decode x)
        (map? x)    (do (when-not (= 1 (count x))
                          (throw (ex-info "Encoded JSON objects have exactly one tag key."
                                          {:value x})))
                        (let [[tag v] (first x)]
                          (decode-tag tag v decode)))
        :else       x))

(defn fragment->json
  "Returns a JSON string for the fragment `frag`, in the encoding documented in
  this namespace (see the table above [[fragment->json]] in the source, or
  DEVELOPING.md). Equal fragments give byte-identical strings."
  [frag]
  (let [{form :emmy.viewer.export/form m :emmy.viewer.export/meta}
        (envelope frag)
        doc #?(:clj  (array-map "format"  format-id
                                "version" version
                                "form"    (encode form)
                                "meta"    (encode m))
               :cljs (js-obj "format"  format-id
                             "version" version
                             "form"    (encode form)
                             "meta"    (encode m)))]
    #?(:clj  (json/write-str doc :escape-slash false)
       :cljs (js/JSON.stringify doc))))

(defn json->fragment
  "Reads a string written by [[fragment->json]] and returns the fragment with its
  metadata restored; see [[edn->fragment]]."
  [s]
  (let [doc #?(:clj  (json/read-str s)
               :cljs (js->clj (js/JSON.parse s)))]
    (when-not (and (map? doc) (= format-id (get doc "format")))
      (throw (ex-info "Not an exported Emmy-Viewers fragment." {:value doc})))
    (when-not (= version (get doc "version"))
      (throw (ex-info "Unsupported fragment export version."
                      {:version (get doc "version") :supported version})))
    (restore-meta (decode (get doc "form"))
                  (decode (get doc "meta")))))

;; ## Static notebook export

#?(:clj
   (defn export-notebook!
     "Static build of ONE Clerk notebook into its own folder, through
  [[emmy.clerk/build!]] (the same build `dev/user.clj`'s `build!` runs for the
  whole documentation site).

  Options:

  - `:notebook` (required): path to the notebook, `.clj`, `.cljc` or `.md`,
    relative to the working directory.
  - `:out-dir` (required): directory to write. When the build finishes it
    holds `index.html`, the notebook's content in `index.edn` (the page fetches
    it, so serve the folder over HTTP; `file://` does not work) and, unless
    `:custom-js` is given, the viewer bundle under `_data/`.

  The viewer JS: by default the bundle is COMPILED from source with
  shadow-cljs (`:cljs-namespaces '[emmy.viewer.clerk-bundle]`), which needs
  the `:cljs` alias next to `:nextjournal/clerk` and node/npm, and takes a few
  minutes. The prebuilt bundle on Clerk's CDN (`emmy.clerk/custom-js`) was
  built for an older Clerk and does not start under the Clerk this project
  pins, so it is not the default; pass `:custom-js <url>` to load a bundle
  from a URL, or your own `:cljs-namespaces`.

  Every other option goes to [[emmy.clerk/build!]] and on to Clerk's `build!`,
  e.g. `:package :single-file` or `:git/sha`. Returns
  `{:notebook :out-dir :index-html}`, `:index-html` being the path of the
  written `index.html`; throws if the build wrote none.

  ```
  clojure -X:nextjournal/clerk:cljs emmy.viewer.export/export-notebook! \\
    :notebook '\"dev/examples/mafs.clj\"' :out-dir '\"target/mafs\"'
  ```"
     [{:keys [notebook out-dir] :as opts}]
     (when-not (and (string? notebook) (string? out-dir))
       (throw (ex-info "export-notebook! needs :notebook and :out-dir strings."
                       {:opts opts})))
     (when-not (.isFile (java.io.File. ^String notebook))
       (throw (ex-info (str "Notebook not found: " notebook)
                       {:notebook notebook})))
     (let [build!     (requiring-resolve 'emmy.clerk/build!)
           index-html (java.io.File. ^String out-dir "index.html")
           js         (if (or (:custom-js opts) (:cljs-namespaces opts))
                        {}
                        {:cljs-namespaces '[emmy.viewer.clerk-bundle]})]
       ;; A stale index.html must not pass for this build's.
       (.delete index-html)
       (build! (merge {:paths   [notebook]
                       :index   notebook
                       :browse? false}
                      js
                      (dissoc opts :notebook :out-dir)
                      {:out-path out-dir}))
       (when-not (.isFile index-html)
         (throw (ex-info (str "Clerk's build wrote no " index-html)
                         {:notebook notebook :out-dir out-dir})))
       {:notebook   notebook
        :out-dir    out-dir
        :index-html (str index-html)})))
