(ns isaac.hail.http
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.walk :as walk]
    [isaac.hail.queue :as queue]))

(defn- read-body [request]
  (let [body (:body request)]
    (cond
      (nil? body)    ""
      (string? body) body
      :else          (slurp body))))

(defn- request-content-type [request]
  (or (get-in request [:headers "content-type"])
      (get-in request [:headers "Content-Type"])
      ""))

(defn- json-content-type? [request]
  (str/includes? (request-content-type request) "application/json"))

(defn- edn-content-type? [request]
  (str/includes? (request-content-type request) "application/edn"))

(defn- response-format [request]
  (if (edn-content-type? request) :edn :json))

(defn- render-body [format body]
  (case format
    :edn  (binding [*print-namespace-maps* false] (pr-str body))
    :json (json/generate-string body)))

(defn- response-content-type [format]
  (case format
    :edn  "application/edn"
    :json "application/json"))

(defn- response [status format body]
  {:status  status
   :headers {"Content-Type" (response-content-type format)}
   :body    (render-body format body)})

(defn- error-response
  ([status format error]
   (error-response status format error nil))
  ([status format error hint]
   (response status format (cond-> {:error error}
                             hint (assoc :hint hint)))))

(defn- parse-body [request]
  (let [body (read-body request)]
    (cond
      (json-content-type? request)
      (try
        (json/parse-string body true)
        (catch Exception _
          ::parse-error))

      (edn-content-type? request)
      (try
        (edn/read-string body)
        (catch Exception _
          ::parse-error))

      :else
      ::unsupported)))

(defn- ->keyword [value]
  (cond
    (keyword? value) value
    (string? value)  (keyword value)
    :else            value))

(defn- field-error [field]
  {:error (str "invalid " (name field))
   :hint  (str "frequencies." (name field) " must be a string or vector of strings")})

(defn- session-id [value]
  (cond
    (keyword? value) (keyword (name value))
    (string? value)  (keyword value)
    :else            ::invalid))

(defn- session-ids [value]
  (cond
    (nil? value)     nil
    (string? value)  [(session-id value)]
    (keyword? value) [(session-id value)]
    (sequential? value)
    (let [ids (mapv session-id value)]
      (if (some #{::invalid} ids) ::invalid ids))
    :else            ::invalid))

(defn- tag-id [value]
  (cond
    (keyword? value) value
    (string? value)  (keyword value)
    :else            ::invalid))

(defn- session-tag-set [value]
  (cond
    (nil? value)     nil
    (string? value)  #{(tag-id value)}
    (keyword? value) #{(tag-id value)}
    (or (set? value) (sequential? value))
    (let [tags (into #{} (map tag-id) value)]
      (if (contains? tags ::invalid) ::invalid tags))
    :else            ::invalid))

(defn- crew-id [value]
  (cond
    (nil? value)     nil
    (string? value)  value
    (keyword? value) (name value)
    :else            ::invalid))

(defn- normalize-frequencies [frequencies]
  (let [frequencies (some-> frequencies walk/keywordize-keys)]
    (if-not (map? frequencies)
      frequencies
      (let [session      (when (contains? frequencies :session)
                           (session-ids (:session frequencies)))
            session-tags (when (contains? frequencies :session-tags)
                           (session-tag-set (:session-tags frequencies)))
            crew         (when (contains? frequencies :crew)
                           (crew-id (:crew frequencies)))]
        (cond
          (= ::invalid session)      (field-error :session)
          (= ::invalid session-tags) (field-error :session-tags)
          (= ::invalid crew)         {:error "invalid crew"
                                      :hint  "frequencies.crew must be a string"}
          :else
          (cond-> frequencies
            (some? session)      (assoc :session session)
            (some? session-tags) (assoc :session-tags session-tags)
            (some? crew)         (assoc :crew crew)
            (:reach frequencies) (update :reach ->keyword)
            (:create frequencies) (update :create ->keyword)
            (:prefer frequencies) (update :prefer ->keyword)))))))

(defn- direct-addressing? [frequencies]
  (and (map? frequencies)
       (boolean (some #(contains? frequencies %)
                      [:session :session-tags :crew]))))

(defn- has-addressing? [frequencies]
  (and (map? frequencies)
       (boolean (some #(contains? frequencies %)
                      [:band :session :session-tags :crew]))))

(defn- validate-record [record]
  (let [frequencies (:frequencies record)]
    (cond
      (:error frequencies)
      (select-keys frequencies [:error :hint])

      (contains? record :crew)
      {:error "invalid crew"
       :hint  "put :crew in :frequencies, not at the hail top level"}

      (not (has-addressing? frequencies))
      {:error "missing frequencies"
       :hint  "include :frequencies with :band, :session, :session-tags, or :crew"}

      (and (direct-addressing? frequencies)
           (not (contains? frequencies :band))
           (str/blank? (:prompt record)))
      {:error "missing prompt"
       :hint  "include :prompt for non-band hails"}

      (and (:reach frequencies) (not (direct-addressing? frequencies)))
      {:error "invalid reach"
       :hint  "include direct addressing when using :reach"})))

(defn- parse-params [value]
  (cond
    (map? value)    value
    (nil? value)    nil
    (string? value) (edn/read-string value)
    :else           value))

(defn- build-record [payload]
  (cond-> {:frequencies (normalize-frequencies (:frequencies payload))
           :from        :http}
    (contains? payload :prompt)    (assoc :prompt (:prompt payload))
    (contains? payload :params)    (assoc :params (parse-params (:params payload)))
    (contains? payload :thread-id) (assoc :thread-id (:thread-id payload))
    (contains? payload :reply-to)  (assoc :reply-to (:reply-to payload))))

(defn handler [request]
  (let [format  (response-format request)
        payload (parse-body request)]
    (cond
      (= ::unsupported payload)
      (error-response 415 :json "unsupported media type" "use application/json or application/edn")

      (= ::parse-error payload)
      (error-response 400 format "invalid body" "request body could not be parsed")

      :else
      (let [record (build-record payload)]
        (if-let [{:keys [error hint]} (validate-record record)]
          (error-response 400 format error hint)
          (let [record (queue/send! record)]
            {:status  201
             :headers {"Content-Type" (response-content-type format)
                       "Location"     (str "/hail/" (:id record))}
             :body    (render-body format record)}))))))