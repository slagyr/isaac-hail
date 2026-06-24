(ns isaac.tool.hail
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.walk :as walk]
    [isaac.hail.queue :as queue]
    [isaac.session.store.spi :as store]
    [isaac.tool.fs-bounds :as bounds]))

(defn- session-crew [args]
  (let [session-key   (get args "session_key")
        session-store (bounds/session-store args)]
    (when session-key
      (some-> (store/get-session session-store session-key)
              :crew))))

(defn- normalize-frequency [frequency]
  (when frequency
    (walk/keywordize-keys frequency)))

(defn- parse-params [value]
  (cond
    (map? value)  value
    (nil? value)  nil
    (string? value) (edn/read-string value)
    :else value))

(defn hail-send-tool
  "Send a hail from the calling crew session.
   Args: frequency, payload, prompt, params, thread-id, reply-to,
   session_key (runtime-injected)."
  [arguments]
  (let [args       (bounds/string-key-map arguments)
        session-key (get args "session_key")
        crew-id    (session-crew args)]
    (if-not crew-id
      {:isError true :error (str "session not found: " session-key)}
      (let [record (cond-> {:frequency (normalize-frequency (get args "frequency"))
                            :from      (keyword (str "crew/" crew-id))}
                     (contains? args "payload") (assoc :payload (get args "payload"))
                     (contains? args "prompt")  (assoc :prompt (get args "prompt"))
                     (contains? args "params")  (assoc :params (parse-params (get args "params")))
                     (contains? args "thread-id") (assoc :thread-id (get args "thread-id"))
                     (contains? args "reply-to")  (assoc :reply-to (get args "reply-to")))]
        {:result (:id (queue/send! record))}))))

(defn hail-send-tool-factory [_]
  {:description "Send a hail to a frequency."
   :parameters  {:type       "object"
                 :properties {"frequency"  {:type "object" :description "Hail address map"}
                              "payload"    {:description "Optional hail payload"}
                              "prompt"     {:type "string" :description "Optional prompt override"}
                              "params"     {:description "Band template parameters (map or EDN string)"}
                              "thread-id"  {:type "string" :description "Optional thread id"}
                              "reply-to"   {:type "string" :description "Optional hail id being replied to"}}
                 :required   ["frequency"]}
   :handler     #'hail-send-tool})