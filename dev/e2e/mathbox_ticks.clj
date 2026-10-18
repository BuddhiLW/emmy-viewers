^{:nextjournal.clerk/visibility {:code :hide}}
(ns e2e.mathbox-ticks
  "Smallest notebook with MathBox tick labels, for dev/e2e/export_render.mjs.

  MathBox's Format primitive calls its `:expr` (the tick-label formatter) with
  eight arguments. Before 0.5.1-fork.1 the default formatter,
  `emmy.viewer.plot/format-number`, had only 1- and 2-arity bodies and threw
  `Invalid arity: 8` for every tick label."
  (:refer-clojure :exclude [+ - * /])
  (:require [emmy.clerk :as ec]
            [emmy.env :refer [*]]
            [emmy.mathbox.plot :as plot]))

^{:nextjournal.clerk/visibility {:code :hide :result :hide}}
(ec/install!)

;; # MathBox tick labels

;; Default axes, with tick labels from `emmy.viewer.plot/format-number`.
;; `:z` is traced symbolically on the JVM, so it uses Emmy's `*`:

(plot/scene
 {:range [[-3 3] [-3 3] [-3 3]]}
 (plot/of-x {:z (fn [x] (* x x 0.25)) :color "Salmon"}))

;; An axis labeled with `emmy.viewer.plot/label-pi`:

(plot/scene
 {:range [[-4 4] [-4 4] [-4 4]]
  :axes {:x {:label-fn 'emmy.viewer.plot/label-pi}
         :y true
         :z true}})
