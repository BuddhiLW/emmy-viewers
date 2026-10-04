(ns emmy.viewer.kernel)

(defn kernel?
  "True when f is callable and implements the batch kernel contract."
  [f]
  (and (fn? f) (fn? (.-batch ^js f))))

(defn ready?
  "True when the kernel is ready to evaluate (including asynchronous backends)."
  [k]
  ((.-ready ^js k)))

(defn dims
  "Return the kernel dimensions as {:state :outputs :params}."
  [k]
  (let [d (.-dims ^js k)]
    {:state (.-state ^js d)
     :outputs (.-outputs ^js d)
     :params (.-params ^js d)}))

(defn batch!
  "Evaluate n rows from xs with parameters ps into out; nil out lets the kernel allocate. Returns the output buffer."
  [k xs n ps out]
  ((.-batch ^js k) xs n ps out))

(defn bind
  "Fix ps in k. A kernel retains its batch, ready and dimensions contract; a plain function stays plain."
  [k ps]
  (let [g (fn [state] (k state ps))]
    (when (kernel? k)
      (set! (.-batch g) (fn [xs n _ignored out]
                          (batch! k xs n ps out)))
      (set! (.-ready g) (fn [] (ready? k)))
      (set! (.-dims g) (.-dims ^js k)))
    g))

(defn bind-1d
  "Fix ps in k and accept a scalar x as a one-element state. Preserve the kernel's batch contract."
  [k ps]
  (let [g (fn [x] (k [x] ps))]
    (when (kernel? k)
      (set! (.-batch g) (fn [xs n _ignored out]
                          (batch! k xs n ps out)))
      (set! (.-ready g) (fn [] (ready? k)))
      (set! (.-dims g) (.-dims ^js k)))
    g))
