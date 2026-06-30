(ns isaac.tool.hail
  (:require
    [clojure.string :as str]
    [clojure.walk :as walk]
    [isaac.hail.queue :as queue]
    [isaac.session.frequencies :as frequencies]
    [isaac.session.store.spi :as store]
    [isaac.tool.fs-bounds :as bounds]))

(defn- session-crew [args]
  (let [session-key   (get args "session_key")
        session-store (bounds/session-store args)]
    (when session-key
      (some-> (store/get-session session-store session-key)
              :crew))))

(def ^:private non-frequency-tool-keys
  #{"session_key" "payload" "prompt" "params" "thread_id" "reply_to"})

(def ^:private address-keys
  (conj frequencies/frequencies-keys :band))

(defn- snake->keyword [s]
  (keyword (str/replace s "_" "-")))

(defn- keyword->snake [kw]
  (str/replace (name kw) "-" "_"))

(defn- one-of-enum [validations]
  (some (fn [v]
          (when (and (vector? v) (= :one-of? (first v)))
            (mapv name (rest v))))
        validations))

(defn- schema-field->json [field-spec]
  (let [base (select-keys field-spec [:description])
        t    (:type field-spec)]
    (case t
      :seq (assoc base
                  :type "array"
                  :items {:type (case (get-in field-spec [:spec :type])
                                  :keyword "string"
                                  :int     "integer"
                                  :boolean "boolean"
                                  "string")})
      :keyword (let [enum (one-of-enum (:validations field-spec))]
                 (cond-> (assoc base :type "string")
                   enum (assoc :enum (vec enum))))
      :int     (assoc base :type "integer")
      :string  (assoc base :type "string")
      (assoc base :type "string"))))

(defn- frequency-tool-properties []
  (into {"band" {:type        "string"
                 :description "Hail band id (config/hail/<band>.edn); routes via the band template"}}
        (map (fn [[field spec]]
               [(keyword->snake field) (schema-field->json spec)])
             frequencies/frequencies-schema)))

(defn- collect-frequencies [args]
  (into {}
        (keep (fn [[k v]]
                (when (and (string? k) (not (contains? non-frequency-tool-keys k)))
                  (let [freq-k (snake->keyword k)]
                    (when (contains? address-keys freq-k)
                      [freq-k v]))))
              args)))

(defn- safe-keyword [x]
  (cond
    (keyword? x) x
    (string? x) (keyword (if (str/starts-with? x ":") (subs x 1) x))
    :else (keyword (str x))))

(defn- normalize-frequencies [frequencies]
  (when frequencies
    (let [frequencies (walk/keywordize-keys frequencies)]
      (cond-> frequencies
        (:session frequencies)
        (update :session #(mapv (fn [x] (if (keyword? x) (name x) (str x)))
                                  (if (sequential? %) % [%])))

        (:session-tags frequencies)
        (update :session-tags #(into #{} (map safe-keyword)
                                     (if (sequential? %) % [%])))

        (:reach frequencies)         (update :reach keyword)
        (:create frequencies)        (update :create keyword)
        (:prefer frequencies)         (update :prefer keyword)
        (:with-context-mode frequencies) (update :with-context-mode keyword)
        (:crew frequencies)          (update :crew str)
        (:with-crew frequencies)     (update :with-crew str)
        (:with-model frequencies)    (update :with-model str)))))

(defn- has-addressing? [frequencies]
  (boolean (some #(contains? frequencies %)
                 [:band :session :session-tags :crew])))

(defn- parse-params [value]
  (when (map? value) value))

(defn hail-send-tool
  "Send a hail from the calling crew session.
   Address keys (band, session, session_tags, crew, …) are flat top-level args
   in snake_case; the handler builds the internal :frequencies map.
   For compatibility, also accepts legacy nested 'frequencies' or 'frequency'.
   Args: band, session, session_tags, crew, reach, prefer, create, with_*,
   payload, prompt, params, thread_id, reply_to, session_key (runtime-injected)."
  [arguments]
  (let [args        (bounds/string-key-map arguments)
        session-key (get args "session_key")
        crew-id     (session-crew args)]
    (if-not crew-id
      {:isError true :error (str "session not found: " session-key)}
      (let [nested (or (get args "frequencies") (get args "frequency"))
            flat (collect-frequencies args)
            freqs (normalize-frequencies (if nested nested flat))]
        (cond
          (not (has-addressing? freqs))
          {:isError true
           :error   "At least one addressing field is required (band, session, session_tags, or crew)"}

          :else
          (let [record (cond-> {:frequencies freqs
                                :from        (keyword (str "crew/" crew-id))}
                         (contains? args "payload")   (assoc :payload (get args "payload"))
                         (contains? args "prompt")    (assoc :prompt (get args "prompt"))
                         (contains? args "params")    (assoc :params (parse-params (get args "params")))
                         (contains? args "thread_id") (assoc :thread-id (get args "thread_id"))
                         (contains? args "reply_to")  (assoc :reply-to (get args "reply_to")))]
            {:result (:id (queue/send! record))}))))))

(defn hail-send-tool-factory [_]
  {:description "Send a hail to a band or session target."
   :parameters  {:type       "object"
                 :properties (merge (frequency-tool-properties)
                                    {"payload"   {:description "Optional hail payload"}
                                     "prompt"    {:type "string" :description "Optional prompt override"}
                                     "params"    {:type "object" :description "Band template parameters as a JSON object"}
                                     "thread_id" {:type "string" :description "Optional thread id"}
                                     "reply_to"  {:type "string" :description "Optional hail id being replied to"}})}
   :handler     #'hail-send-tool})