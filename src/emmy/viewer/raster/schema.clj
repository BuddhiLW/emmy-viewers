(ns emmy.viewer.raster.schema
  "Malli contracts of the raster backend, as uncompiled malli data.

  The schemas are a projection over the domain: enums derive from the domain's
  own constants, so they cannot drift. Function schemas are declared here with
  `m/=>` against the domain functions, which stay malli-free; they are inert
  until [[instrument!]] turns them on for a scoped, reversible run (tests, dev).

  No registry is installed globally. The one recursive schema, [[Shape]],
  carries its own local `:registry`."
  (:require [emmy.viewer.raster.glue :as glue]
            [emmy.viewer.raster.lower :as lower]
            [emmy.viewer.raster.plan :as plan]
            [malli.core :as m]
            [malli.instrument :as mi]))

(def Convention
  "A calling convention shared with Emmy's compiler."
  (into [:enum] plan/conventions))

(def Backend
  "A computational backend. Backends are an OPEN set (a new one is a defmethod
  of `emmy.viewer.compile/compiled-fn*`), so this is any keyword, never an
  enum."
  :keyword)

(def Expr
  "A frozen Emmy expression, or its lowered raster form."
  [:or number? :symbol [:sequential :any]])

(def Shape
  "The output tree with each leaf replaced by its index; nil for a scalar."
  [:schema {:registry {::shape [:or nat-int? [:vector [:ref ::shape]]]}}
   [:maybe ::shape]])

(def Plan
  "Everything the backend decides about a function before raster sees it."
  [:map {:closed true}
   [:convention Convention]
   [:state [:vector :symbol]]
   [:params [:vector :symbol]]
   [:outputs [:vector Expr]]
   [:shape Shape]])

(def CompileOpts
  "The options of `emmy.expression.compile/compile-state-fn` the backend reads.
  Open: other keys pass through to Emmy."
  [:map
   [:calling-convention {:optional true} Convention]
   [:generic-params? {:optional true} :boolean]
   [:simplify? {:optional true} :boolean]
   [:arity {:optional true} nat-int?]])

(def KernelForm
  "The source of one `raster.core/deftm` kernel."
  [:and seq? [:fn #(= 'raster.core/deftm (first %))]])

(def Module
  "A wasm module as base64."
  :string)

(m/=> emmy.viewer.raster.plan/plan [:=> [:cat ifn? [:or false? [:sequential :any]] :any CompileOpts] Plan])

(m/=> emmy.viewer.raster.lower/lower [:=> [:cat [:sequential :symbol] Expr] Expr])

(m/=> emmy.viewer.raster.lower/kernel-form [:=> [:cat :symbol Plan] KernelForm])

(m/=> emmy.viewer.raster.glue/glue [:=> [:cat Plan Module :boolean] :string])

(def ^:private nses
  '#{emmy.viewer.raster.plan emmy.viewer.raster.lower emmy.viewer.raster.glue})

(defn instrument!
  "Checks every call to the raster backend's domain functions against its
  schema, until [[unstrument!]]. Scoped to this backend's namespaces."
  []
  (mi/instrument! {:filters [(apply mi/-filter-ns nses)]}))

(defn unstrument!
  "Reverses [[instrument!]]."
  []
  (mi/unstrument! {:filters [(apply mi/-filter-ns nses)]}))
