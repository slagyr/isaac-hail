(ns isaac.hail.delivery-worker
  (:require
    [clojure.edn :as edn]
    [clojure.pprint :as pprint]
    [clojure.string :as str]
    [isaac.bridge.core :as bridge]
    [isaac.bridge.suspend :as suspend]
    [isaac.charge :as charge]
    [isaac.comm.null :as null-comm]
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.drive.turn :as turn]
    [isaac.fs :as fs]
    [isaac.hail.attention :as attention]
    [isaac.hail.beans-status :as beans-status]
    [isaac.hail.band-resolve :as band-resolve]
    [isaac.hail.prepare :as hail-prepare]
    [isaac.hail.router :as router]
    [isaac.logger :as log]
    [isaac.naming :as naming]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]
    [isaac.session.context :as session-ctx]
    [isaac.session.frequencies :as session-frequencies]
    [isaac.session.store.spi :as store]
    [isaac.tool.memory :as memory])
  (:import
    (java.time Instant)))

(def default-tick-ms 1000)

(def default-max-continuations 3)

(def ^:private hail-guidance
  "Autonomous hail; the user may not see your reply.")

(def ^:private delays-ms
  {1 1000
   2 5000
   3 30000
   4 120000
   5 600000})

(defn- write-edn [value]
  (binding [*print-namespace-maps* false]
    (with-out-str (pprint/pprint value))))

(defn- runtime-root [opts]
  (or (:root opts)
      (nexus/get :root)
      (root/current-root)
      (loader/root)
      (throw (ex-info "hail delivery worker requires :root" {}))))

(defn- filesystem []
  (or (fs/instance)
      (throw (ex-info "hail delivery worker requires :fs in system" {}))))

(defn- deliveries-dir [root]
  (str root "/hail/deliveries"))

(defn- delivered-dir [root]
  (str root "/hail/delivered"))

(defn- failed-dir [root]
  (str root "/hail/failed"))

(defn- record-path [dir id]
  (str dir "/" id ".edn"))

(defn- temp-path [path]
  (str path ".tmp"))

(defn- normalize-id [value]
  (cond
    (keyword? value) (name value)
    (string? value)  value
    (nil? value)     nil
    :else            (str value)))

(defn- id-keyword [value]
  (some-> value normalize-id keyword))

(defn- read-record [path]
  (let [fs* (filesystem)]
    (when (fs/exists? fs* path)
      (edn/read-string (fs/slurp fs* path)))))

(defn- write-record! [path record]
  (let [fs*  (filesystem)
        temp (temp-path path)]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit fs* temp (write-edn record))
    (fs/move fs* temp path)))

(defn- delete-record! [path]
  (fs/delete (filesystem) path))

(defn- list-records-in-dir [root dir-path]
  (let [fs* (filesystem)]
    (if-let [children (fs/children fs* dir-path)]
      (->> children
           (map #(read-record (str dir-path "/" %)))
           (remove nil?)
           (sort-by :id)
           vec)
      [])))

(defn- list-deliveries [root]
  (list-records-in-dir root (deliveries-dir root)))

(defn- due? [record now]
  (if-let [next-attempt-at (:next-attempt-at record)]
    (not (.isAfter (Instant/parse next-attempt-at) now))
    true))

(defn- crew-config [cfg crew-id]
  (or (get-in cfg [:crew crew-id])
      (get-in cfg [:crew (keyword crew-id)])))

(defn- crew-max-in-flight [cfg crew-id]
  (or (:max-in-flight (crew-config cfg crew-id)) 1))

(defn- crew-available? [cfg session-store crew-id]
  (< (store/in-flight-count session-store crew-id)
     (crew-max-in-flight cfg crew-id)))

(defn- session-available? [cfg session-store session-id]
  (when-let [session (store/get-session session-store session-id)]
    (let [crew-id (normalize-id (:crew session))]
      (and (not (store/in-flight? session-store session-id))
           (crew-available? cfg session-store crew-id)
           session))))

;; A routed delivery file IS the hail (flat — enriched in place by the router);
;; there is no :hail wrapper. A reach-:all child also carries :source-hail (it
;; rides along; the worker treats the child like any other delivery).
(defn- delivery-band [cfg delivery]
  (when-let [band-name (get-in delivery [:frequencies :band])]
    (get (band-resolve/resolved-slice (:hail cfg)) band-name)))

(defn- bind-candidate [cfg delivery session]
  ;; Binding lands the delivery in an existing session — run it in THAT
  ;; session's crew context. The delivery's routed :crew (a cfg-default like
  ;; :main for an unbound/spawn delivery) must not shadow the session crew, so
  ;; resolve as if the delivery carried no crew override.
  (let [band (delivery-band cfg delivery)]
    (-> delivery
        (assoc :crew (router/effective-crew cfg band (dissoc delivery :crew) session)
               :bound-session (router/state-id-value (:id session)))
        (dissoc :candidates))))

(defn- create-delivery? [cfg delivery]
  (let [band (delivery-band cfg delivery)]
    (and (= :one (router/effective-reach band delivery))
         (= :if-missing (router/effective-create band delivery)))))

(defn- matching-spawn-sessions [cfg session-store delivery]
  (let [band     (delivery-band cfg delivery)
        sessions (store/list-sessions session-store)]
    (:sessions (router/matching-sessions band sessions delivery))))

(defn- available-spawn-session [cfg session-store delivery]
  (some #(session-available? cfg session-store (normalize-id (:id %)))
        (matching-spawn-sessions cfg session-store delivery)))

(defn- create-session! [session-store delivery host-crew]
  (let [root (runtime-root {})
        name (naming/generate (naming/->SequentialStrategy root "sessions" "session-" (filesystem)))]
    (session-ctx/create-with-resolved-behavior!
     name
     (merge {:crew          host-crew
             :tags          (router/normalize-tags (get-in delivery [:frequencies :session-tags]))
             :origin        {:kind :hail
                             :hail-id (normalize-id (:id delivery))}
             :session-store session-store}
            (session-frequencies/behavioral-override (:frequencies delivery))))))

(defn- spawn-target [cfg session-store delivery]
  (if-let [session (available-spawn-session cfg session-store delivery)]
    {:action :bind :session session}
    (if (seq (matching-spawn-sessions cfg session-store delivery))
      {:action :wait}
      (let [crew-id (normalize-id (:crew delivery))]
        (if (crew-available? cfg session-store crew-id)
          {:action :spawn :crew-id crew-id}
          {:action :wait})))))

(defn- spawn-runnable-delivery [cfg session-store delivery]
  (let [{:keys [action session crew-id]} (spawn-target cfg session-store delivery)]
    (case action
      :bind  (bind-candidate cfg delivery session)
      :spawn (bind-candidate cfg delivery (create-session! session-store delivery crew-id))
      nil)))

(defn- runnable-delivery [cfg session-store delivery]
  (cond
    (create-delivery? cfg delivery)
    (spawn-runnable-delivery cfg session-store delivery)

    :else
    (if-let [session-id (normalize-id (:bound-session delivery))]
      (when (session-available? cfg session-store session-id)
        delivery)
      (some (fn [{:keys [session]}]
              (when-let [session-entry (session-available? cfg session-store (normalize-id session))]
                (bind-candidate cfg delivery session-entry)))
            (:candidates delivery)))))

(defn- delivery-path [root id]
  (record-path (deliveries-dir root) id))

(defn- delivered-path [root id]
  (record-path (delivered-dir root) id))

(defn- failed-path [root id]
  (record-path (failed-dir root) id))

(defn- finish-delivered! [root delivery]
  (write-record! (delivered-path root (:id delivery)) delivery))

(defn- finish-failed! [root delivery]
  (write-record! (failed-path root (:id delivery)) delivery))

(defn- backoff-ms [attempts]
  (get delays-ms attempts))

(defn- exception-error-info [e]
  {:error      :exception
   :ex-class   (.getName (class e))
   :ex-message (.getMessage e)})

(defn- failure-log-context [error]
  (cond
    (map? error)   (select-keys error [:error :ex-class :ex-message])
    (keyword? error) {:error error}
    :else          {:error error}))

(defn- dead-letter! [cfg root delivery attempts error]
  (let [failed (merge delivery {:attempts attempts} (failure-log-context error))]
    (finish-failed! root failed)
    (attention/maybe-notify-dead-letter! cfg failed error)
    (log/error :hail/dead-lettered
               (merge {:id        (:id delivery)
                       :thread-id (:thread-id delivery)
                       :session   (normalize-id (:bound-session delivery))
                       :attempts  attempts
                       :reason    :exhausted}
                      (failure-log-context error)))))

(defn- max-continuations [cfg opts]
  (or (:max-continuations opts)
      (get-in cfg [:hail-settings :max-continuations])
      default-max-continuations))

(defn- continuation-notice [n max-n]
  (str "continuation " n " of " max-n
       "; send the 🔁 at-a-glance, then continue — do not restart"))

(def ^:private limbo-notice
  "Your previous turn ended without a terminal action (no handoff hail, bean not completed) — complete the handoff or escalation your skill prescribes.")

(defn- continuations-exhausted! [cfg root delivery]
  (let [failed (assoc delivery :reason :continuations-exhausted)]
    (finish-failed! root failed)
    (attention/maybe-notify-dead-letter! cfg failed :continuations-exhausted)
    (log/error :hail/dead-lettered
               {:id            (:id delivery)
                :thread-id     (:thread-id delivery)
                :session       (normalize-id (:bound-session delivery))
                :continuations (:continuations delivery 0)
                :reason        :continuations-exhausted})))

(defn- queue-continuation! [root cfg opts delivery]
  (let [max-c   (max-continuations cfg opts)
        current (:continuations delivery 0)]
    (if (>= current max-c)
      (continuations-exhausted! cfg root delivery)
      (let [next    (inc current)
            updated (assoc delivery
                           :continuations next
                           :prompt (str (continuation-notice next max-c)
                                        "\n\n"
                                        (:prompt delivery)))]
        (write-record! (delivery-path root (:id delivery)) updated)
        (log/warn :hail/continuation-queued
                  :id (:id delivery)
                  :thread-id (:thread-id delivery)
                  :session (normalize-id (:bound-session delivery))
                  :continuations next)))))

(defn- queue-limbo-continuation! [root cfg opts delivery]
  (let [max-c   (max-continuations cfg opts)
        current (:continuations delivery 0)]
    (if (>= current max-c)
      (continuations-exhausted! cfg root delivery)
      (let [next    (inc current)
            base    (:prompt delivery)
            notice  (str (continuation-notice next max-c)
                         "\n\n"
                         limbo-notice)
            updated (assoc delivery
                           :continuations next
                           :prompt (str notice "\n\n" base))]
        (write-record! (delivery-path root (:id delivery)) updated)
        (log/warn :hail/limbo-continuation-queued
                  :id (:id delivery)
                  :thread-id (:thread-id delivery)
                  :session (normalize-id (:bound-session delivery))
                  :continuations next)))))

(defn- defer-delivery! [root now delivery retry-after-ms & {:keys [reason provider cfg]}]
  (let [reason (or reason :wall)]
    (write-record! (delivery-path root (:id delivery))
                   (assoc delivery
                          :next-attempt-at (str (.plusMillis now retry-after-ms))))
    (log/warn :hail/delivery-deferred
              :id (:id delivery)
              :thread-id (:thread-id delivery)
              :session (normalize-id (:bound-session delivery))
              :reason reason
              :retry-after-ms retry-after-ms)
    (when (= :auth reason)
      (attention/maybe-notify-auth! cfg provider (.toEpochMilli now)))
    (when (= :context-exhausted reason)
      (attention/maybe-notify-context-exhausted!
       cfg (normalize-id (:bound-session delivery)) (.toEpochMilli now)))))

(defn- reschedule! [cfg root now delivery error]
  (let [attempts (inc (:attempts delivery 0))]
    (if-let [delay-ms (backoff-ms attempts)]
      (if (= attempts 5)
        (dead-letter! cfg root delivery attempts error)
        (do
          (write-record! (delivery-path root (:id delivery))
                         (assoc delivery
                                :attempts attempts
                                :next-attempt-at (str (.plusMillis now delay-ms))))
          (log/warn :hail/attempt-failed
                    (merge {:id        (:id delivery)
                            :thread-id (:thread-id delivery)
                            :session   (normalize-id (:bound-session delivery))
                            :attempts  attempts}
                           (failure-log-context error)))))
      (dead-letter! cfg root delivery attempts error))))

(defn- hail-origin [hail]
  (let [hail-id (normalize-id (:id hail))]
    (cond-> {:kind :hail :hail-id hail-id}
      (:thread-id hail) (assoc :thread-id (normalize-id (:thread-id hail)))
      (:reply-to hail)  (assoc :reply-to (normalize-id (:reply-to hail)))
      (:params hail)    (assoc :params (:params hail))
      (:data hail)      (assoc :data (:data hail))
      (:prompt hail)    (assoc :prompt (:prompt hail)))))

(defn- delivery-with-prompt [cfg delivery]
  (hail-prepare/render-band-prompt delivery cfg))

(defn- meta-line [label value]
  (when (some? value) (str label ": " (normalize-id value))))

(defn- metadata-preamble
  "The delivery turn's system-preamble guidance: the autonomy line plus a
   model-friendly metadata block — hail id, thread, submitter/reply-to origin,
   and the hail's :params as data. Session identity lives in the cached system
   prompt; this block carries only per-delivery facts. :params are ALWAYS echoed
   here — even when a band template already consumed them — so they never silently
   drop on the explicit-prompt path."
  [delivery]
  (let [{:keys [id thread-id reply-to submitter-session params data]} delivery
        lines (->> [(meta-line "Hail id" id)
                    (meta-line "Thread" thread-id)
                    (meta-line "Submitter session" submitter-session)
                    (meta-line "Reply-to" reply-to)
                    (meta-line "From crew" (or (:from-crew delivery) (:from delivery)))
                    (when (seq data) (str "Data: " (pr-str data)))
                    (when (seq params) (str "Params: " (pr-str params)))]
                   (remove nil?))]
    (str/join "\n" (concat [hail-guidance "--- Hail metadata ---"] lines))))

(defn- delivery-charge [cfg delivery]
  (let [override (session-frequencies/behavioral-override (:frequencies delivery))]
    (charge/build {:config         cfg
                   :comm           null-comm/channel
                   :guidance       (metadata-preamble delivery)
                   :session-key    (normalize-id (:bound-session delivery))
                   :input          (:prompt delivery)
                   :origin         (hail-origin delivery)
                   :crew           (or (:crew override) (normalize-id (:crew delivery)))
                   :model-override (:model override)})))

;; Claim = the bridge records a durable turn marker (isaac-7li9), THEN we delete
;; the delivery file. A crash between them leaves marker + stray delivery — the
;; stale-delivery guard in tick! removes the stray when the marker is orphaned
;; (no live in-flight entry), never re-dispatches it. A live failure-reschedule
;; transiently recreates the same pair; the in-flight gate keeps the guard off
;; until finally clears the marker. The marker is cleared (and the in-flight
;; gate released) in the finally.
(defn- launch-delivery! [opts delivery]
  (let [cfg           (:cfg opts)
        session-store (:session-store opts)
        root          (runtime-root opts)
        delivery      (delivery-with-prompt cfg delivery)
        session-id    (normalize-id (:bound-session delivery))
        charge        (assoc (delivery-charge cfg delivery) :hail-delivery delivery)
        run!          (nexus/bound-runtime-fn
                        (bound-fn []
                          (try
                            (let [result (if (charge/unresolved? charge)
                                           {:error (:charge/reason charge)}
                                           (turn/run-turn! charge))]
                              (cond
                                (suspend/suspended-response? result)
                                (log/info :hail/delivery-suspended
                                          :id (:id delivery)
                                          :thread-id (:thread-id delivery)
                                          :session session-id)

                                (:unavailable? result)
                                (defer-delivery! root (:now opts) delivery (:retry-after-ms result)
                                                 {:reason   (or (:reason result) :wall)
                                                  :provider (:provider result)
                                                  :cfg      cfg})

                                (= :tool-loop-limit (:ended-by result))
                                (queue-continuation! root cfg opts delivery)

                                (:error result)
                                (reschedule! cfg root (:now opts) delivery (:error result))

                                (beans-status/turn-in-limbo? cfg delivery result)
                                (queue-limbo-continuation! root cfg opts delivery)

                                :else
                                (do
                                  (finish-delivered! root delivery)
                                  (log/info :hail/delivered
                                            :id (:id delivery)
                                            :thread-id (:thread-id delivery)
                                            :session session-id)))
                              result)
                            (catch Exception e
                              (let [err (exception-error-info e)]
                                (reschedule! cfg root (:now opts) delivery err)
                                err))
                            (finally
                              (bridge/clear-turn-marker! session-store session-id)
                              (store/clear-in-flight! session-store session-id)))))]
    (when (store/mark-in-flight! session-store session-id)
      (bridge/record-turn-marker! session-store session-id charge)
      (delete-record! (delivery-path root (:id delivery)))
      (log/info :hail/bound
                :id (:id delivery)
                :thread-id (:thread-id delivery)
                :session session-id
                :crew (normalize-id (:crew delivery))
                :attempts (:attempts delivery 0))
      (future (run!)))))

(defn- referenced-delivery-ids
  "Map of delivery-id -> turn marker for every marker that already claims a
   delivery — used to drop stray deliveries instead of re-dispatching them."
  [session-store]
  (into {} (keep (fn [m] (when-let [did (:delivery-id m)] [did m]))
                 (store/turn-markers session-store))))

(defn tick!
  ;; A tick is a wake boundary: config may have changed while we slept, so we
  ;; read the current snapshot here (an entry-point read). The resolved cfg is
  ;; then threaded as a value into each in-flight delivery — we never write the
  ;; snapshot back.
  [{:keys [cfg session-store now] :as opts}]
  (let [cfg           (or cfg (loader/snapshot "hail delivery tick wake boundary — config may have changed") {})
        root     (runtime-root opts)
        session-store (or session-store
                          (store/registered-store)
                          (throw (ex-info "hail delivery worker requires :session-store or registered [:sessions :store]" {})))
        now           (or now (memory/now))
        opts*         (assoc opts :cfg cfg :root root :session-store session-store :now now)
        referenced    (referenced-delivery-ids session-store)]
    (->> (list-deliveries root)
         (filter #(due? % now))
         (keep (fn [delivery]
                 (if-let [marker (get referenced (str (:id delivery)))]
                   (if (store/in-flight? session-store (:session-id marker))
                     ;; live turn: failure-reschedule rewrote deliveries/ before
                     ;; finally cleared the marker — not a claim-crash stray (isaac-3tyl)
                     nil
                     ;; orphaned marker: claim-time crash stray — drop, never
                     ;; re-dispatch (isaac-7li9)
                     (do
                       (delete-record! (delivery-path root (:id delivery)))
                       (log/warn :hail/stale-delivery-removed
                                 :session (:session-id marker)
                                 :id (:id delivery))
                       nil))
                   (when-let [runnable (runnable-delivery cfg session-store delivery)]
                     (launch-delivery! opts* runnable)))))
         vec)))

(defn start!
  [{:keys [tick-ms]
    :or   {tick-ms default-tick-ms}
    :as   opts}]
  (let [shared-scheduler (or (nexus/get :scheduler)
                             (throw (ex-info "hail delivery worker requires :scheduler in isaac.nexus" {})))]
    (scheduler/schedule! shared-scheduler
                         {:id      :hail/deliver
                          :trigger {:kind :interval :ms tick-ms}
                          :handler (fn [_] (tick! {}))})
    {:scheduler shared-scheduler
     :task-id   :hail/deliver}))


(defn requeue!
  "Move hail/failed/<id> back to hail/deliveries/ with attempts reset and provenance."
  [root id & {:keys [now-ms]}]
  (let [path   (failed-path root id)
        failed (read-record path)]
    (when-not failed
      (throw (ex-info (str "hail requeue: " id " not found in failed/") {:id id})))
    (let [error-kw (or (:error failed)
                       (when (= :continuations-exhausted (:reason failed))
                         :continuations-exhausted))
          resurrected (-> failed
                          (dissoc :error :reason :ex-class :ex-message :next-attempt-at)
                          (assoc :attempts 0
                                 :requeued-from id
                                 :requeued-error error-kw
                                 :requeued-at (str (or (some-> now-ms Instant/ofEpochMilli)
                                                       (Instant/now)))))]
      (delete-record! path)
      (write-record! (delivery-path root id) resurrected)
      resurrected)))

(defn stop! [{:keys [scheduler task-id]}]
  (when scheduler
    (scheduler/cancel! scheduler task-id)))
