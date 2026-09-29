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
  (and band-name (str/starts-with? (name band-name) "_")))

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
  {:key   (str "hail." (if (or (keyword? band-id) (string? band-id) (symbol? band-id))
                         (name band-id)
                         (str band-id)))
   :value message})

(defn- base-name [band]
  (some-> (:base band) str str/trim not-empty))

(defn resolve-band
  "Resolve one band against raw bands, following base: transitively."
  [band-id band raw-bands visited]
  (when-not (map? band)
    (throw (ex-info "hail band is not a map"
                    {:band band-id :type :hail-band/not-a-map})))
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
    (fn [{:keys [bands errors]} entry]
      (let [[band-id band] (if (and (sequential? entry) (>= (count entry) 2))
                             entry
                             [nil nil])]
        (if-not (map? band)
          {:bands bands :errors errors}
          (if (contains? band :reach)
            {:bands bands :errors (conj errors (error-row band-id "unknown key :reach"))}
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

                  (throw e)))))))))
    {:bands {} :errors []}
    raw-slice))

(defn- resolution-errors
  [root-schema hail-slice]
  (let [entity-schema (schema-compose/schema-for-kind root-schema :hail)
        {:keys [bands errors]} (resolve-slice hail-slice)
        validate-errors (mapcat (fn [[k v]]
                                  (when (map? v)
                                    (validate-resolved-band entity-schema k v)))
                                bands)]
    {:bands bands :errors (vec (concat errors validate-errors))}))

(defn- reach-error-rows
  "Detect the removed :reach key from a raw (un-conformed) hail slice.
   Conform silently drops unknown keys, so :reach can only be caught here —
   never used to resolve or validate bands, only to report this one error."
  [raw-hail]
  (reduce (fn [errors [band-id band]]
            (if (and (map? band) (contains? band :reach))
              (conj errors (error-row band-id "unknown key :reach"))
              errors))
          []
          raw-hail))

(defn resolved-slice
  "Resolve hail band inheritance from a raw :hail config slice. Returns only
   addressable bands (templates omitted). Safe to call on an already-resolved
   slice — bands without :base pass through unchanged."
  [raw-slice]
  (if (empty? raw-slice)
    {}
    (let [settings (into {} (remove (fn [[_ v]] (map? v)) raw-slice))]
      (merge settings (:bands (resolve-slice raw-slice))))))

(defn check-config
  "isaac.config/check contribution — surface inheritance errors at validate time.
   The raw slice is read only to detect the removed :reach key (conform drops
   unknown keys silently); bands are resolved and validated from the
   conformed (:hail config), never from raw un-coerced values."
  [{:keys [config result effective-schema]}]
  (let [conformed-hail (:hail config)
        raw-hail (get-in result [:raw :hail])]
    (if (and (empty? conformed-hail) (empty? raw-hail))
      {:errors [] :warnings []}
      {:errors (into (reach-error-rows raw-hail)
                     (:errors (resolution-errors effective-schema conformed-hail)))
       :warnings []})))

(defn apply-to-load-result!
  "Post-process a config load result: resolve hail band inheritance from the
   conformed (:hail config). The raw slice is read only to detect the removed
   :reach key; it never overrides a conformed band value."
  [root-schema {:keys [config] :as result}]
  (let [conformed-hail (:hail config)
        raw-hail (get-in result [:raw :hail])]
    (if (and (empty? conformed-hail) (empty? raw-hail))
      result
      (let [{:keys [bands errors]} (resolution-errors root-schema conformed-hail)
            settings (into {} (remove (fn [[_ v]] (map? v)) conformed-hail))]
        (cond-> result
          true (assoc-in [:config :hail] (merge settings bands))
          true (update :errors into (into (reach-error-rows raw-hail) errors)))))))