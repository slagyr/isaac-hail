(ns isaac.hail.queue
  "Stateless hail ingress: expand a band, submit a durable Agent turn, return its id."
  (:require
    [clojure.edn :as edn]
    [clojure.pprint :as pprint]
    [clojure.string :as str]
    [isaac.foundation.config.loader :as loader]
    [isaac.hail.band-resolve :as bands]
    [isaac.hail.prepare :as prepare]
    [isaac.foundation.logger :as log]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus]
    [isaac.agent.turn.queue :as turns]
    [isaac.agent.turn.submit :as submit])
  (:import (java.util UUID)))

(defn check-readable!
  "Validate that submitted values survive Agent's EDN persistence."
  [record]
  (let [encoded (binding [*print-namespace-maps* false] (with-out-str (pprint/pprint record)))]
    (try
      (when-not (= record (edn/read-string encoded))
        (throw (ex-info "serialized form does not read back to the same value" {})))
      (catch Exception e
        (throw (ex-info (str "hail record is unreadable: " (ex-message e))
                        {:type :hail/unreadable-record}))))
    record))

(defn- band-entry [cfg band-name]
  (let [resolved (bands/resolved-slice (:hail cfg))]
    (or (get resolved band-name) (get resolved (keyword band-name)))))

(defn- effective-frequencies [record band]
  (let [submitted (:frequencies record)
        band-freqs (select-keys band [:session :session-tags :crew :prefer :create
                                      :with-crew :with-model :with-effort :with-context-mode])
        direct? (seq (:session submitted))
        freqs (merge (if direct? (select-keys band-freqs [:with-crew :with-model :with-effort :with-context-mode]) band-freqs) (dissoc submitted :band))]
    (cond-> (assoc freqs :create (or (:create freqs) :never))
      (:session freqs) (update :session #(mapv (fn [id] (if (keyword? id) (name id) (str id))) %))
      (:session-tags freqs) (update :session-tags set))))

(defn- thread-id [record]
  (or (:thread-id record)
      (when-let [reply-to (:reply-to record)]
        (let [parent (turns/read-held (str reply-to))]
          (when-not parent
            (throw (ex-info (str "turn not found: " reply-to) {:reason :turn-not-found})))
          (or (get-in parent [:origin :thread-id]) (:id parent))))))

(defn- metadata-preamble [record id]
  (str/join "\n" (remove nil?
                          ["Autonomous hail; the user may not see your reply."
                           "--- Hail metadata ---"
                           (str "Hail id: " id)
                           (str "Thread: " (or (:thread-id record) id))
                           (when-let [v (:submitter-session record)] (str "Submitter session: " v))
                           (when-let [v (:reply-to record)] (str "Reply-to: " v))
                           (when-let [v (:from record)] (str "From crew: " v))
                           (when (seq (:data record)) (str "Data: " (pr-str (:data record))))
                           (when (seq (:params record)) (str "Params: " (pr-str (:params record))))])))

(defn send!
  "Submit a hail directly into the Agent waiting room. No Hail state is written."
  [record]
  (let [snapshot  (loader/snapshot "hail send")
        cfg       (if (seq (:hail snapshot)) snapshot
                      (or (:config (loader/load-config-result
                                     {:root (nexus/get :root) :fs (or (nexus/get :fs) (fs/instance))}))
                          snapshot {}))
        band-name (get-in record [:frequencies :band])
        band      (when band-name (band-entry cfg band-name))
        _         (when (and band-name (nil? band))
                    (throw (ex-info (str "unknown band: " band-name) {:reason :unknown-band})))
        record    (-> record (prepare/render-band-prompt cfg) (prepare/enrich-band-data cfg))
        _         (check-readable! record)
        thread    (thread-id record)
        freqs     (effective-frequencies record band)
        cycle     (:cycle band)
        id        (subs (str/replace (str (UUID/randomUUID)) "-" "") 0 8)
        origin    (cond-> {:source :hail :from (:from record) :params (:params record) :data (:data record)
                           :thread-id (or thread id)}
                    band-name (assoc :band band-name)
                    (:principal record) (assoc :principal (:principal record))
                    (:reply-to record) (assoc :reply-to (:reply-to record))
                    (:submitter-session record) (assoc :submitter-session (:submitter-session record)))
        preamble  (metadata-preamble (assoc record :thread-id (:thread-id origin)) id)
        accepted  (try
                    (submit/submit! {:root (nexus/get :root) :config cfg :frequencies freqs
                                     :id id :prompt (:prompt record) :key (:idempotency-key record)
                                     :origin origin :preamble preamble :cycle cycle})
                    (catch clojure.lang.ExceptionInfo e
                      (when (and band-name (= :no-match (:reason (ex-data e))))
                        (log/warn :hail/undeliverable :band band-name :reason :no-recipients))
                      (throw e)))]
    (log/info :hail/sent :id (:id accepted) :thread-id (get-in accepted [:origin :thread-id])
              :frequencies freqs :from (:from record))
    accepted))
