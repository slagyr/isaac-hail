(ns isaac.hail.router
  (:require
    [clojure.edn :as edn]
    [clojure.pprint :as pprint]
    [clojure.set :as set]
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.fs :as fs]
    [isaac.hail.band-resolve :as band-resolve]
    [isaac.hail.queue :as queue]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]
    [isaac.session.frequencies :as session-frequencies]
    [isaac.session.store.spi :as session-store]))

(def default-tick-ms 1000)

(defn- write-edn [value]
  (binding [*print-namespace-maps* false]
    (with-out-str (pprint/pprint value))))

(defn- runtime-root []
  (or (nexus/get :root)
      (root/current-root)
      (loader/root)
      (throw (ex-info "hail router requires :root" {}))))

(defn- filesystem []
  (or (fs/instance) (throw (ex-info "hail router requires :fs in system" {}))))

(defn- require-session-store [opts]
  (or (:session-store opts)
      (session-store/registered-store)
      (throw (ex-info "hail router requires :session-store or registered [:sessions :store]" {}))))

(defn- pending-dir []
  (str (runtime-root) "/hail/pending"))

(defn- deliveries-dir []
  (str (runtime-root) "/hail/deliveries"))

(defn- undeliverable-dir []
  (str (runtime-root) "/hail/undeliverable"))

(defn- broadcasts-dir []
  (str (runtime-root) "/hail/broadcasts"))

(defn- pending-path [id]
  (str (pending-dir) "/" id ".edn"))

(defn- delivery-path [id]
  (str (deliveries-dir) "/" id ".edn"))

(defn- undeliverable-path [id]
  (str (undeliverable-dir) "/" id ".edn"))

(defn- broadcast-path [id]
  (str (broadcasts-dir) "/" id ".edn"))

(defn- temp-path [path]
  (str path ".tmp"))

(defn- read-record [path]
  (let [fs* (filesystem)]
    (when (fs/exists? fs* path)
      (try
        (edn/read-string (fs/slurp fs* path))
        (catch Exception e
          (log/error :hail/bad-record :path path :error (.getMessage e))
          nil)))))

(defn- write-record! [path record]
  (let [fs*  (filesystem)
        temp (temp-path path)]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit fs* temp (write-edn record))
    (fs/move fs* temp path)))

(defn- delete-pending! [id]
  (fs/delete (filesystem) (pending-path id)))

(defn- list-pending []
  (let [fs* (filesystem)
        dir (pending-dir)]
    (if-let [children (fs/children fs* dir)]
      (->> children
           (map (fn [p]
                  (try
                    (read-record (str dir "/" p))
                    (catch Exception e
                      (log/error :hail/bad-pending-record :path p :error (.getMessage e))
                      nil))))
           (remove nil?)
           (sort-by :id)
           vec)
      [])))

(defn normalize-id [value]
  (cond
    (keyword? value) (name value)
    (string? value)  value
    (nil? value)     nil
    :else            (str value)))

(defn id-keyword [value]
  (some-> value normalize-id keyword))

(defn state-id-value [value]
  (let [id (normalize-id value)]
    (if (and (string? id) (re-matches #"[a-z][a-z-]*" id))
      (keyword id)
      id)))

(defn normalize-tags [tags]
  (set (map keyword (or tags #{}))))

(defn- frequencies-ids [value]
  (set (keep id-keyword value)))

(defn- frequencies-tags [value]
  (normalize-tags value))

(defn- intersect-or [left right]
  (cond
    (and (seq left) (seq right)) (set/intersection left right)
    (seq left)                   left
    (seq right)                  right
    :else                        nil))

(defn- union-or [left right]
  (cond
    (and (seq left) (seq right)) (set/union left right)
    (seq left)                   left
    (seq right)                  right
    :else                        nil))

(defn- hail-frequencies [hail]
  (or (:frequencies hail) {}))

(defn- band-frequencies [band]
  (when band
    (select-keys band [:session :session-tags :crew :reach :prefer :create
                       :with-crew :with-model :with-effort :with-context-mode])))

(defn- frequency-crew-set [value]
  (when-let [id (id-keyword value)]
    #{id}))

(defn- merge-session-ids [band hail]
  (let [band-ids (frequencies-ids (:session band))
        hail-ids (frequencies-ids (:session hail))]
    (cond
      (and (seq band-ids) (seq hail-ids)) (vec (set/intersection band-ids hail-ids))
      (seq hail-ids)                      (vec hail-ids)
      (seq band-ids)                      (vec band-ids)
      :else                               nil)))

(defn- merge-session-tags [band hail]
  (let [merged (union-or (frequencies-tags (:session-tags band))
                         (frequencies-tags (:session-tags hail)))]
    (when (seq merged) (vec merged))))

(defn- merge-crew [band hail]
  (let [band-crew (frequency-crew-set (:crew band))
        hail-crew (frequency-crew-set (:crew hail))]
    (cond
      (and band-crew hail-crew)
      (first (set/intersection band-crew hail-crew))

      hail-crew (first hail-crew)
      band-crew (first band-crew)
      :else     nil)))

(defn- explicit-session-hail? [hail]
  (boolean (seq (frequencies-ids (:session (hail-frequencies hail))))))

(defn- merge-with-overrides [band hail]
  (let [band* (band-frequencies band)
        hail* (hail-frequencies hail)]
    (cond-> {}
      (or (:with-crew hail*) (:with-crew band*))
      (assoc :with-crew (or (:with-crew hail*) (:with-crew band*)))

      (or (:with-model hail*) (:with-model band*))
      (assoc :with-model (or (:with-model hail*) (:with-model band*)))

      (or (:with-effort hail*) (:with-effort band*))
      (assoc :with-effort (or (:with-effort hail*) (:with-effort band*)))

      (or (:with-context-mode hail*) (:with-context-mode band*))
      (assoc :with-context-mode (or (:with-context-mode hail*) (:with-context-mode band*))))))

(defn- explicit-session-frequencies [band hail]
  (let [hail* (hail-frequencies hail)]
    (cond-> (merge {:session (vec (frequencies-ids (:session hail*)))
                    :reach   :one}
                   (merge-with-overrides band hail))
      (:create hail*) (assoc :create (:create hail*)))))

(defn- merged-band-hail-frequencies [band hail]
  (let [band* (band-frequencies band)
        hail* (hail-frequencies hail)]
    (merge
      (cond-> {}
        (seq (merge-session-ids band* hail*))
        (assoc :session (merge-session-ids band* hail*))

        (seq (merge-session-tags band* hail*))
        (assoc :session-tags (merge-session-tags band* hail*))

        (merge-crew band* hail*)
        (assoc :crew (merge-crew band* hail*))

        (or (:reach hail*) (:reach hail) (:reach band*))
        (assoc :reach (or (:reach hail*) (:reach hail) (:reach band*) :one))

        (or (:prefer hail*) (:prefer band*))
        (assoc :prefer (or (:prefer hail*) (:prefer band*)))

        (or (:create hail*) (:create band*))
        (assoc :create (or (:create hail*) (:create band*))))
      (merge-with-overrides band hail))))

(defn effective-frequencies
  "Merge band defaults with hail :frequencies into one shared selector map.
   When the hail names :session explicitly, that id is the complete recipient
   coordinate — band session selectors do not further constrain it."
  [band hail]
  (if (explicit-session-hail? hail)
    (explicit-session-frequencies band hail)
    (merged-band-hail-frequencies band hail)))

(defn effective-reach [band hail]
  (if (explicit-session-hail? hail)
    :one
    (or (:reach hail)
        (get-in hail [:frequencies :reach])
        (:reach band)
        :one)))

(defn effective-create [band hail]
  (if (explicit-session-hail? hail)
    (or (get-in hail [:frequencies :create]) :never)
    (or (:create hail)
        (get-in hail [:frequencies :create])
        (:create band)
        :never)))

(defn- delivery-frequencies [band hail]
  (merge (hail-frequencies hail) (effective-frequencies band hail)))

(defn- has-session-frequencies? [frequencies]
  (boolean (or (seq (:session frequencies))
               (seq (:session-tags frequencies))
               (:crew frequencies))))

(defn effective-crew
  "Resolve the processing crew for a matched session: :with-crew override,
   session :crew, cfg [:defaults :crew] (default :main)."
  [cfg band hail session]
  (or (id-keyword (get-in hail [:frequencies :with-crew]))
      (id-keyword (:with-crew (band-frequencies band)))
      (id-keyword (:crew session))
      (id-keyword (get-in cfg [:defaults :crew]))
      :main))

(defn spawn-crew
  "Resolve the processing crew for a create delivery (no session yet)."
  [cfg band hail]
  (or (id-keyword (get-in hail [:frequencies :with-crew]))
      (id-keyword (:with-crew (band-frequencies band)))
      (id-keyword (get-in cfg [:defaults :crew]))
      :main))

(defn- sort-by-prefer [sessions prefer]
  (let [sorted (sort-by :updated-at sessions)]
    (case (or prefer :recent)
      :oldest sorted
      :recent (reverse sorted))))

(defn matching-sessions [band sessions hail]
  (let [band-name   (get-in hail [:frequencies :band])
        frequencies (effective-frequencies band hail)]
    (cond
      (and band-name (nil? band))
      {:reason :unknown-band}

      (not (has-session-frequencies? frequencies))
      {:reason :no-recipients}

      :else
      {:sessions (session-frequencies/matching-sessions frequencies sessions)})))

;; ----- Obligation resolution (pure) -----
;;
;; An id is identity: a routed hail keeps its id and filename. resolve-obligations
;; enriches the hail IN PLACE (flat — no :hail wrapper) and returns one of:
;;   {:delivery flat-hail}              reach :one bound / unbound pool / create
;;   {:broadcast {:parent hail
;;                :children [{:crew :session} ...]}}  reach :all (child ids minted at write)
;;   {:undeliverable flat-hail}         routing failure, carries :reason

(defn- bound-delivery [cfg band hail session]
  (assoc hail
         :frequencies (delivery-frequencies band hail)
         :crew        (effective-crew cfg band hail session)
         :bound-session (state-id-value (:id session))
         :attempts    0))

(defn- create-delivery [cfg band hail]
  (assoc hail
         :frequencies (delivery-frequencies band hail)
         :crew        (spawn-crew cfg band hail)
         :bound-session nil
         :attempts    0))

(defn- candidate-entry [cfg band hail session]
  {:crew    (effective-crew cfg band hail session)
   :session (state-id-value (:id session))})

(defn- order-candidates [matches prefer]
  (if prefer
    (sort-by-prefer matches prefer)
    (sort-by :id matches)))

(defn- unbound-delivery [cfg band hail matches prefer]
  (assoc hail
         :frequencies (delivery-frequencies band hail)
         :crew        nil
         :bound-session nil
         :candidates  (mapv #(candidate-entry cfg band hail %)
                              (order-candidates matches prefer))
         :attempts    0))

(defn resolve-obligations [cfg bands sessions hail]
  (let [band-name    (get-in hail [:frequencies :band])
        band         (when band-name (get bands band-name))
        reach        (effective-reach band hail)
        create       (effective-create band hail)
        frequencies  (effective-frequencies band hail)
        prefer       (:prefer frequencies)
        match-result (matching-sessions band sessions hail)
        matches      (:sessions match-result)]
    (cond
      (:reason match-result)
      {:undeliverable (assoc hail :reason (:reason match-result))}

      (empty? matches)
      (if (and (= :if-missing create) (= :one reach))
        {:delivery (create-delivery cfg band hail)}
        {:undeliverable (assoc hail :reason :no-recipients)})

      (= :all reach)
      {:broadcast {:parent   hail
                   :children (mapv #(candidate-entry cfg band hail %)
                                   (sort-by :id matches))}}

      (= 1 (count matches))
      {:delivery (bound-delivery cfg band hail (first matches))}

      :else
      {:delivery (unbound-delivery cfg band hail matches prefer)})))

;; ----- Write phase (moves the hail file by lifecycle stage) -----

(defn- write-delivery! [delivery]
  (write-record! (delivery-path (:id delivery)) delivery)
  (delete-pending! (:id delivery)))

(defn- write-undeliverable! [hail]
  (write-record! (undeliverable-path (:id hail)) hail)
  (delete-pending! (:id hail)))

(defn- write-broadcast! [root fs* parent child-addrs]
  (let [parent-id (:id parent)
        children  (mapv (fn [addr]
                          (assoc parent
                                 :id          (queue/next-id root fs*)
                                 :source-hail parent-id
                                 :crew        (:crew addr)
                                 :bound-session (:session addr)
                                 :attempts    0))
                        child-addrs)]
    (doseq [child children]
      (write-record! (delivery-path (:id child)) child))
    (write-record! (broadcast-path parent-id)
                   (assoc parent :children (mapv :id children)))
    (delete-pending! parent-id)))

(defn tick!
  [{:keys [cfg root] :as opts}]
  (let [cfg            (or cfg (loader/snapshot "hail router tick wake boundary — config may have changed") {})
        root           (or root (runtime-root))
        fs*            (filesystem)
        session-store* (require-session-store opts)
        bands          (band-resolve/resolved-slice (:hail cfg))
        sessions       (session-store/list-sessions session-store*)]
    (doseq [hail (list-pending)]
      (let [{:keys [delivery broadcast undeliverable]}
            (resolve-obligations cfg bands sessions hail)]
        (cond
          delivery
          (do (write-delivery! delivery)
              (log/info :hail/routed
                        :id (:id delivery)
                        :thread-id (:thread-id delivery)
                        :band (get-in delivery [:frequencies :band])
                        :outcome :delivery
                        :session (:bound-session delivery)
                        :candidates (count (:candidates delivery))))

          broadcast
          (let [parent (:parent broadcast) children (:children broadcast)]
            (write-broadcast! root fs* parent children)
            (log/info :hail/routed
                      :id (:id parent)
                      :thread-id (:thread-id parent)
                      :band (get-in parent [:frequencies :band])
                      :outcome :broadcast
                      :children (count children)))

          undeliverable
          (do (write-undeliverable! undeliverable)
              (log/warn :hail/undeliverable
                        :id (:id undeliverable)
                        :thread-id (:thread-id undeliverable)
                        :band (get-in undeliverable [:frequencies :band])
                        :reason (:reason undeliverable)))

          :else
          (let [u (assoc hail :reason :no-recipients)]
            (write-undeliverable! u)
            (log/warn :hail/undeliverable
                      :id (:id u)
                      :thread-id (:thread-id u)
                      :band (get-in u [:frequencies :band])
                      :reason (:reason u))))))))

(defn start!
  [{:keys [tick-ms]
    :or   {tick-ms default-tick-ms}}]
  (let [shared-scheduler (or (nexus/get :scheduler)
                             (throw (ex-info "hail router requires :scheduler in isaac.nexus" {})))]
    (scheduler/schedule! shared-scheduler
                         {:id      :hail/route
                          :trigger {:kind :interval :ms tick-ms}
                          :handler (fn [_] (tick! {}))})
    {:scheduler shared-scheduler
     :task-id   :hail/route}))

(defn stop! [{:keys [scheduler task-id]}]
  (when scheduler
    (scheduler/cancel! scheduler task-id)))