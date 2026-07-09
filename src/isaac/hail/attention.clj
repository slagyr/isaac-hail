(ns isaac.hail.attention
  (:require
    [clojure.string :as str]
    [isaac.comm.delivery.queue :as queue]
    [isaac.logger :as log]))

(def ^:private throttle-ms (* 60 60 1000))

(defonce ^:private last-notified* (atom {}))

(defn clear-throttle!
  "Test hook — reset per-provider attention throttle state."
  []
  (reset! last-notified* {}))

(defn- provider-key [provider]
  (keyword (or provider :unknown)))

(defn- attention-message [provider]
  (str "Provider auth failure (" (name (provider-key provider)) "): check subscription login or token."))

(defn- normalize-comm-coords [raw]
  (when raw
    (cond
      (and (map? raw) (or (:id raw) (:comm raw)))
      {:comm   (or (:id raw) (:comm raw))
       :target (or (:channel raw) (:target raw))}

      (string? raw)
      {:comm raw :target nil}

      :else nil)))

(defn- notification-comm-from-data [data]
  (some-> data :notification-comm normalize-comm-coords))

(defn- dead-letter-message [delivery error]
  (let [bean-id (or (get-in delivery [:params :bean-id])
                    (get-in delivery [:data :bean-id])
                    (get-in delivery [:params "bean-id"]))
        err-kw  (cond
                  (keyword? error) (name error)
                  (map? error)     (some-> (:error error) name)
                  :else            (str error))
        parts   (remove str/blank?
                        [(when bean-id (str bean-id " dead-letter"))
                         (when-let [tid (:thread-id delivery)] (str "thread " tid))
                         (when-let [a (:attempts delivery)] (str "attempts " a))
                         (when err-kw (str "error " err-kw))])]
    (str/join " — " parts)))

(defn maybe-notify-dead-letter!
  "Post unthrottled dead-letter attention to delivery :data :notification-comm,
   else :attention :notify, else warn only."
  [cfg delivery error]
  (let [coords (or (notification-comm-from-data (:data delivery))
                   (get-in cfg [:attention :notify]))]
    (if (and (:comm coords) (:target coords))
      (queue/enqueue! {:comm    (:comm coords)
                       :target  (:target coords)
                       :content (dead-letter-message delivery error)})
      (log/warn :hail/dead-letter-attention-unconfigured
                :id (:id delivery)))))

(defn maybe-notify-auth!
  "Post throttled auth attention to the configured comm outbox, or warn when unset."
  [cfg provider now-ms]
  (if-let [{:keys [comm target]} (get-in cfg [:attention :notify])]
    (let [key  (provider-key provider)
          last (get @last-notified* key 0)]
      (when (>= (- now-ms last) throttle-ms)
        (swap! last-notified* assoc key now-ms)
        (queue/enqueue! {:comm    comm
                         :target  target
                         :content (attention-message provider)})))
    (log/warn :hail/auth-attention-unconfigured
              :provider provider)))
