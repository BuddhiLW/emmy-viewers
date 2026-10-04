(ns emmy.viewer.kernel
  "The browser side of the Kernel contract: the only API viewers use to talk to a
  compiled function.

  Every kernel, whichever backend compiled it, is a JS function callable in
  Emmy's calling convention that also carries:

  - `batch(xs, n, ps, out)`: evaluates `n` points (their flattened states,
    row-major in `xs`) and returns the outputs, row-major, in a Float64Array;
  - `ready()`: whether the kernel can evaluate yet (an asynchronously compiled
    module is not ready at first);
  - `dims`: `{state, outputs, params}`.

  Viewers call these functions instead of the properties, so a backend can
  change how it meets the contract without any viewer changing.")

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

(defn- forward-contract!
  "Gives `g`, a per-point function derived from kernel `k` with parameters `ps`
  fixed, the rest of `k`'s contract. The property names are those JS reads, so
  the writes carry ^js and survive advanced compilation."
  [^js g k ps]
  (when (kernel? k)
    (set! (.-batch g) (fn [xs n _ignored out] (batch! k xs n ps out)))
    (set! (.-ready g) (fn [] (ready? k)))
    (set! (.-dims g) (.-dims ^js k)))
  g)

(defn bind
  "Fix ps in k. A kernel retains its batch, ready and dimensions contract; a plain function stays plain."
  [k ps]
  (forward-contract! (fn [state] (k state ps)) k ps))

(defn bind-1d
  "Fix ps in k and accept a scalar x as a one-element state. Preserve the kernel's batch contract."
  [k ps]
  (forward-contract! (fn [x] (k [x] ps)) k ps))
