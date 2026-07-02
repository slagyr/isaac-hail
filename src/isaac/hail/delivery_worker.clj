(ns isaac.hail.delivery-worker
  (:require
    [clojure.edn :as edn]
    [clojure.pprint :as pprint]
    [clojure.string :as str]
    [isaac.charge :as charge]
    [isaac.comm.null :as null-comm]
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.drive.turn :as turn]
    [isaac.fs :as fs]
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

(defn- inflight-dir [root]
  (str root "/hail/inflight"))

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

(defn- list-deliveries [root]
  (let [fs* (filesystem)
        dir (deliveries-dir root)]
    (if-let [children (fs/children fs* dir)]
      (->> children
           (map #(read-record (str dir "/" %)))
           (remove nil?)
           (sort-by :id)
           vec)
      [])))

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
               :session (router/state-id-value (:id session)))
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
    (if-let [session-id (normalize-id (:session delivery))]
      (when (session-available? cfg session-store session-id)
        delivery)
      (some (fn [{:keys [session]}]
              (when-let [session-entry (session-available? cfg session-store (normalize-id session))]
                (bind-candidate cfg delivery session-entry)))
            (:candidates delivery)))))

(defn- inflight-path [root id]
  (record-path (inflight-dir root) id))

(defn- delivery-path [root id]
  (record-path (deliveries-dir root) id))

(defn- delivered-path [root id]
  (record-path (delivered-dir root) id))

(defn- failed-path [root id]
  (record-path (failed-dir root) id))

(defn- claim-delivery! [root delivery]
  (write-record! (inflight-path root (:id delivery)) delivery)
  (delete-record! (delivery-path root (:id delivery)))
  delivery)

(defn- finish-delivered! [root delivery]
  (write-record! (delivered-path root (:id delivery)) delivery)
  (delete-record! (inflight-path root (:id delivery))))

(defn- finish-failed! [root delivery]
  (write-record! (failed-path root (:id delivery)) delivery)
  (delete-record! (inflight-path root (:id delivery))))

(defn- backoff-ms [attempts]
  (get delays-ms attempts))

(defn- reschedule! [root now delivery]
  (let [attempts (inc (:attempts delivery 0))]
    (if-let [delay-ms (backoff-ms attempts)]
      (if (= attempts 5)
        (do
          (finish-failed! root (assoc delivery :attempts attempts))
          (log/error :hail/dead-lettered :id (:id delivery) :reason :exhausted))
        (do
          (write-record! (delivery-path root (:id delivery))
                         (assoc delivery
                                :attempts attempts
                                :next-attempt-at (str (.plusMillis now delay-ms))))
          (delete-record! (inflight-path root (:id delivery)))))
      (do
        (finish-failed! root (assoc delivery :attempts attempts))
        (log/error :hail/dead-lettered :id (:id delivery) :reason :exhausted)))))

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
                   :session-key    (normalize-id (:session delivery))
                   :input          (:prompt delivery)
                   :origin         (hail-origin delivery)
                   :crew           (or (:crew override) (normalize-id (:crew delivery)))
                   :model-override (:model override)})))

(defn- run-delivery! [cfg delivery]
  (let [charge (delivery-charge cfg delivery)]
    (if (charge/unresolved? charge)
      {:error (:charge/reason charge)}
      (turn/run-turn! charge))))

(defn- launch-delivery! [opts delivery]
  (let [cfg           (:cfg opts)
        session-store (:session-store opts)
        root          (runtime-root opts)
        delivery      (delivery-with-prompt cfg delivery)
        session-id    (normalize-id (:session delivery))
        run!          (nexus/bound-runtime-fn
                        (bound-fn []
                          (try
                            (let [result (run-delivery! cfg delivery)]
                              (if (:error result)
                                (reschedule! root (:now opts) delivery)
                                (finish-delivered! root delivery))
                              result)
                            (catch Exception e
                              (reschedule! root (:now opts) delivery)
                              {:error :exception :message (.getMessage e)})
                            (finally
                              (store/clear-in-flight! session-store session-id)))))]
    (when (store/mark-in-flight! session-store session-id)
      (claim-delivery! root delivery)
      (future (run!)))))

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
        now           (or now (memory/now))]
    (->> (list-deliveries root)
         (filter #(due? % now))
         (map #(runnable-delivery cfg session-store %))
         (remove nil?)
         (map #(launch-delivery! (assoc opts
                                   :cfg cfg
                                   :now now
                                   :session-store session-store
                                   :root root)
                                 %))
         (remove nil?)
         vec)))

(defn start!
  [{:keys [tick-ms]
    :or   {tick-ms default-tick-ms}}]
  (let [shared-scheduler (or (nexus/get :scheduler)
                             (throw (ex-info "hail delivery worker requires :scheduler in isaac.nexus" {})))]
    (scheduler/schedule! shared-scheduler
                         {:id      :hail/deliver
                          :trigger {:kind :interval :ms tick-ms}
                          :handler (fn [_] (tick! {}))})
    {:scheduler shared-scheduler
     :task-id   :hail/deliver}))

(defn stop! [{:keys [scheduler task-id]}]
  (when scheduler
    (scheduler/cancel! scheduler task-id)))
