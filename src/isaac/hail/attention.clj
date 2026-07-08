(ns isaac.hail.attention
  (:require
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