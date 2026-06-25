(ns isaac.hail.router
  (:require
    [clojure.edn :as edn]
    [clojure.pprint :as pprint]
    [clojure.set :as set]
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.fs :as fs]
    [isaac.hail.queue :as queue]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]
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
  (or (fs/instance) (throw (ex-info "hail.router requires :fs in system" {}))))

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
      (edn/read-string (fs/slurp fs* path)))))

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
           (map #(read-record (str dir "/" %)))
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

(defn- selector-ids [selector]
  (set (keep id-keyword selector)))

(defn- selector-tags [selector]
  (normalize-tags selector))

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

(defn effective-reach [band hail]
  (or (:reach hail)
      (get-in hail [:frequency :reach])
      (:reach band)
      :one))

(defn effective-spawn [band hail]
  (or (:spawn-session hail)
      (get-in hail [:frequency :spawn-session])
      (:spawn-session band)
      false))

(defn- effective-id-filter [band hail key]
  (intersect-or (selector-ids (get band key))
                (selector-ids (get-in hail [:frequency key]))))

(defn- effective-tag-filter [band hail key]
  (union-or (selector-tags (get band key))
            (selector-tags (get-in hail [:frequency key]))))

(defn- crew-selector-set [value]
  (when-let [id (id-keyword value)]
    #{id}))

(defn- effective-crew-filter [band hail]
  (intersect-or (crew-selector-set (:crew band))
                (crew-selector-set (get-in hail [:frequency :crew]))))

(defn- has-session-selector? [session-ids session-tags crew-ids]
  (boolean (or (seq session-ids) (seq session-tags) (seq crew-ids))))

(defn effective-crew
  "Resolve the processing crew for a matched session: session :crew, then cfg
   [:defaults :crew] (default :main)."
  [cfg _band _hail session]
  (or (id-keyword (:crew session))
      (id-keyword (get-in cfg [:defaults :crew]))
      :main))

(defn spawn-crew
  "Resolve the processing crew for a spawn delivery (no session yet)."
  [cfg _band _hail]
  (or (id-keyword (get-in cfg [:defaults :crew]))
      :main))

(defn matching-sessions [band sessions hail]
  (let [band-name    (get-in hail [:frequency :band])
        session-ids  (effective-id-filter band hail :session)
        session-tags (effective-tag-filter band hail :session-tags)
        crew-ids     (effective-crew-filter band hail)]
    (cond
      (and band-name (nil? band))
      {:reason :unknown-band}

      (not (has-session-selector? session-ids session-tags crew-ids))
      {:reason :no-recipients}

      :else
      {:sessions
       (->> sessions
            (filter (fn [session]
                      (let [session-id (id-keyword (:id session))
                            crew-id    (id-keyword (:crew session))]
                        (and
                          (or (nil? session-ids) (contains? session-ids session-id))
                          (or (nil? session-tags)
                              (every? #(contains? (session-store/tags-of session) %) session-tags))
                          (or (nil? crew-ids) (contains? crew-ids crew-id))))))
            (sort-by :id)
            vec)})))

;; ----- Obligation resolution (pure) -----
;;
;; An id is identity: a routed hail keeps its id and filename. resolve-obligations
;; enriches the hail IN PLACE (flat — no :hail wrapper) and returns one of:
;;   {:delivery flat-hail}              reach :one bound / unbound pool / spawn
;;   {:broadcast {:parent hail
;;                :children [{:crew :session} ...]}}  reach :all (child ids minted at write)
;;   {:undeliverable flat-hail}         routing failure, carries :reason

(defn- bound-delivery [cfg band hail session]
  (assoc hail
         :crew     (effective-crew cfg band hail session)
         :session  (state-id-value (:id session))
         :attempts 0))

(defn- spawn-delivery [cfg band hail]
  (assoc hail
         :crew     (spawn-crew cfg band hail)
         :session  nil
         :attempts 0))

(defn- candidate-entry [cfg band hail session]
  {:crew    (effective-crew cfg band hail session)
   :session (state-id-value (:id session))})

(defn- unbound-delivery [cfg band hail matches]
  (assoc hail
         :crew       nil
         :session    nil
         :candidates (mapv #(candidate-entry cfg band hail %) matches)
         :attempts   0))

(defn resolve-obligations [cfg bands sessions hail]
  (let [band-name    (get-in hail [:frequency :band])
        band         (when band-name (get bands band-name))
        reach        (effective-reach band hail)
        spawn?       (effective-spawn band hail)
        match-result (matching-sessions band sessions hail)
        matches      (:sessions match-result)]
    (cond
      (:reason match-result)
      {:undeliverable (assoc hail :reason (:reason match-result))}

      (empty? matches)
      (if (and spawn? (= :one reach))
        {:delivery (spawn-delivery cfg band hail)}
        {:undeliverable (assoc hail :reason :no-recipients)})

      (= :all reach)
      {:broadcast {:parent   hail
                   :children (mapv #(candidate-entry cfg band hail %) matches)}}

      (= 1 (count matches))
      {:delivery (bound-delivery cfg band hail (first matches))}

      :else
      {:delivery (unbound-delivery cfg band hail matches)})))

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
                                 :session     (:session addr)
                                 :attempts    0))
                        child-addrs)]
    (doseq [child children]
      (write-record! (delivery-path (:id child)) child))
    (write-record! (broadcast-path parent-id)
                   (assoc parent :children (mapv (comp symbol :id) children)))
    (delete-pending! parent-id)))

(defn tick!
  [{:keys [cfg root] :as opts}]
  (let [cfg            (or cfg (loader/snapshot "hail router tick wake boundary — config may have changed") {})
        root           (or root (runtime-root))
        fs*            (filesystem)
        session-store* (or (:session-store opts)
                           (session-store/registered-store)
                           (session-store/create root))
        bands          (:hail cfg)
        sessions       (session-store/list-sessions session-store*)]
    (doseq [hail (list-pending)]
      (let [{:keys [delivery broadcast undeliverable]}
            (resolve-obligations cfg bands sessions hail)]
        (cond
          delivery      (write-delivery! delivery)
          broadcast     (write-broadcast! root fs* (:parent broadcast) (:children broadcast))
          undeliverable (write-undeliverable! undeliverable)
          :else         (write-undeliverable! (assoc hail :reason :no-recipients)))))))

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