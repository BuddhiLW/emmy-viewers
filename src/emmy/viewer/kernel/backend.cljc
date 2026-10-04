(ns emmy.viewer.kernel.backend
  "The open contract and registry for viewer computational backends.")

(defprotocol KernelBackend
  (kernel-form [backend f params initial-state opts]
    "A form that evaluates, in the browser, to the kernel."))

(defonce ^:private backends (atom {}))

(defn register-backend!
  "Register a KernelBackend under `k`. Passing nil removes a registration."
  [k backend]
  (when (and (some? backend) (not (satisfies? KernelBackend backend)))
    (throw (ex-info "Expected a KernelBackend" {:backend backend :key k})))
  (swap! backends (if (nil? backend) dissoc assoc) k backend)
  backend)

(defn resolve-backend
  "Resolve a registered keyword, or return a KernelBackend value unchanged.
  The :raster alias loads its optional JVM implementation on first use."
  [k-or-backend]
  (if (keyword? k-or-backend)
    (let [k k-or-backend]
      #?(:clj
         (when (and (= k :raster) (not (contains? @backends k)))
           (try
             (requiring-resolve 'emmy.viewer.raster/compiled-fn)
             (catch java.io.FileNotFoundException e
               (throw (ex-info "Compile backend :raster requires raster on the classpath"
                               {:backend :raster} e))))))
      (or (get @backends k)
          (throw (ex-info (str "Unknown compile backend " (pr-str k)
                               "; known: " (pr-str (sort (keys @backends))))
                          {:backend k}))))
    (if (satisfies? KernelBackend k-or-backend)
      k-or-backend
      (throw (ex-info (str "Unknown compile backend " (pr-str k-or-backend)
                           "; known: " (pr-str (sort (keys @backends))))
                      {:backend k-or-backend})))))