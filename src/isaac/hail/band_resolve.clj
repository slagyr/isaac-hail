(ns isaac.hail.band-resolve
  (:require
    [c3kit.apron.schema :as cs]
    [clojure.string :as str]
    [isaac.config.schema-base :as schema-base]
    [isaac.config.schema-compose :as schema-compose]
    [isaac.schema.lexicon :as lexicon]))

(defn template-band?
  "Band files whose names start with _ are templates — inherited from, not hailed."
  [band-name]
  (str/starts-with? (name band-name) "_"))

(defn addressable-band?
  [band-name]
  (not (template-band? band-name)))

(defn merge-bands
  "One-level map merge: map-valued keys merge key-wise; scalars/vectors replace."
  [base child]
  (merge-with (fn [a b]
                (if (and (map? a) (map? b))
                  (merge a b)
                  b))
              base
              child))

(defn- error-row [band-id message]
  {:key   (str "hail." (name band-id))
   :value message})

(defn- base-name [band]
  (some-> (:base band) str str/trim not-empty))

(defn resolve-band
  "Resolve one band against raw bands, following base: transitively."
  [band-id band raw-bands visited]
  (let [base-id (base-name band)]
    (if-not base-id
      (dissoc band :base)
      (if (contains? visited band-id)
        (throw (ex-info "hail band base cycle"
                        {:band band-id :visited visited :type :hail-band/cycle}))
        (if-let [base-band (get raw-bands base-id)]
          (let [resolved-base (resolve-band base-id base-band raw-bands (conj visited band-id))]
            (-> (merge-bands resolved-base band)
                (dissoc :base)))
          (throw (ex-info "hail band base not found"
                          {:band band-id :base base-id :type :hail-band/missing-base})))))))

(defn- validate-resolved-band [entity-schema band-id band]
  (let [entity (lexicon/conform (schema-base/strip-validation-annotations entity-schema) band)]
    (if (cs/error? entity)
      [(error-row band-id (pr-str (cs/message-map entity)))]
      [])))

(defn resolve-slice
  "Resolve bands in raw-slice. Templates are validated but omitted from :bands."
  [raw-slice]
  (reduce
    (fn [{:keys [bands errors]} [band-id band]]
      (try
        (let [resolved (resolve-band band-id band raw-slice #{})]
          (if (template-band? band-id)
            {:bands bands :errors errors}
            {:bands (assoc bands band-id resolved) :errors errors}))
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (case (:type data)
              :hail-band/cycle
              {:bands  bands
               :errors (conj errors (error-row band-id
                                                (str "base cycle: "
                                                     (str/join " -> " (map name (:visited data)))
                                                     " -> " (name band-id))))}

              :hail-band/missing-base
              {:bands  bands
               :errors (conj errors (error-row band-id
                                                (str "missing base band: " (:base data))))}

              (throw e))))))
    {:bands {} :errors []}
    raw-slice))

(defn apply-to-load-result!
  "Post-process a config load result: resolve hail band inheritance."
  [root-schema {:keys [config] :as result}]
  (let [raw-hail (:hail config)]
    (if (empty? raw-hail)
      result
      (let [entity-schema (schema-compose/schema-for-kind root-schema :hail)
            {:keys [bands errors]} (resolve-slice raw-hail)
            validate-errors (mapcat #(validate-resolved-band entity-schema (key %) (val %))
                                    bands)]
        (cond-> result
          true (assoc-in [:config :hail] bands)
          true (update :errors into (concat errors validate-errors)))))))