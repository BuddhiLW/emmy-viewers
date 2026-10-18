(ns emmy.viewer.plot
  "Plotting utilities shared between the various viewing libraries.")

(defn format-number
  "Given a number `x`, returns a string truncating `x` to have at most `n` decimal
  places.

  `n` defaults to 2.

  Called with three or more arguments, `format-number` formats `x` with the
  default precision and ignores the rest. MathBox calls a `Format` `:expr` as
  `(expr x y z w i j k l)`, where `y` is a data channel, not a precision."
  ([x] (format-number x 2))
  ([x n]
   (-> (.toFixed x n)
       (.replace #"\.0+$" "")))
  ([x _ _ & _] (format-number x 2)))

(defn label-pi
  "Given a numeric value `x`, returns a string formatting `x` as a multiple of
  `π`.

  Values close to `π` will receive no prefix; any other number will print with
  at most `precision` decimal places.

  Called with three or more arguments (as MathBox calls a `Format` `:expr`),
  formats `x` with the default precision and ignores the rest."
  ([x] (label-pi x 5))
  ([x precision]
   (cond (zero? x)"0"
         (< (Math/abs (- Math/PI x)) 0.001) "π"
         (< (Math/abs (- (- Math/PI) x)) 0.001) "-π"
         :else
         (let [prefix (-> (/ x Math/PI)
                          (format-number precision))]
           (case prefix
             "1"  "π"
             "-1" "-π"
             (str prefix "π")))))
  ([x _ _ & _] (label-pi x 5)))
