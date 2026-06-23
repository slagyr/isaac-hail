(ns isaac.hail.router
  (:require
    [clojure.edn :as edn]
    [clojure.pprint :as pprint]
    [clojure.set :as set]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.naming :as naming]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]
    [isaac.session.store.spi :as session-store]))

(def default-tick-ms 1000)

(defn- write-edn [value]
  (binding [*print-namespace-maps* false]
    (with-out-str (pprint/pprint value))))

(defn- runtime-root []
  (or (loader/root) (throw (ex-info "hail router requires :root" {}))))

(defn- filesystem []
  (or (fs/instance) (throw (ex-info "hail.router requires :fs in system" {}))))

(defn- pending-dir []
  (str (runtime-root) "/hail/pending"))

(defn- deliveries-dir []
  (str (runtime-root) "/hail/deliveries"))

(defn- undeliverable-dir []
  (str (runtime-root) "/hail/undeliverable"))

(defn- pending-path [id]
  (str (pending-dir) "/" id ".edn"))

(defn- delivery-path [id]
  (str (deliveries-dir) "/" id ".edn"))

(defn- undeliverable-path [id]
  (str (undeliverable-dir) "/" id ".edn"))

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

(defn- delivery-id [root fs*]
  (naming/generate (naming/->SequentialStrategy root "hail/deliveries" "delivery-" fs*)))

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

(defn effective-crew
  "Resolve the processing crew after session match: hail :crew, band :crew,
   session :crew, then cfg [:defaults :crew] (default :main)."
  [cfg band hail session]
  (or (id-keyword (:crew hail))
      (id-keyword (:crew band))
      (id-keyword (:crew session))
      (id-keyword (get-in cfg [:defaults :crew]))
      :main))

(defn spawn-crew
  "Resolve the processing crew for a spawn delivery (no session yet)."
  [cfg band hail]
  (or (id-keyword (:crew hail))
      (id-keyword (:crew band))
      (id-keyword (get-in cfg [:defaults :crew]))
      :main))

(defn matching-sessions [band sessions hail]
  (let [band-name    (get-in hail [:frequency :band])
        session-ids  (effective-id-filter band hail :session)
        session-tags (effective-tag-filter band hail :session-tags)]
    (cond
      (and band-name (nil? band))
      {:reason :unknown-band}

      :else
      {:sessions
       (->> sessions
            (filter (fn [session]
                      (let [session-id (id-keyword (:id session))]
                        (and
                          (or (nil? session-ids) (contains? session-ids session-id))
                          (or (nil? session-tags)
                              (every? #(contains? (session-store/tags-of session) %) session-tags))))))
            (sort-by :id)
            vec)})))

(defn- bound-delivery [cfg band hail session]
  {:hail     hail
   :crew     (effective-crew cfg band hail session)
   :session  (state-id-value (:id session))
   :attempts 0})

(defn- candidate-entry [cfg band hail session]
  {:crew    (effective-crew cfg band hail session)
   :session (state-id-value (:id session))})

(defn resolve-obligations [cfg bands sessions hail]
  (let [band-name    (get-in hail [:frequency :band])
        band         (when band-name (get bands band-name))
        reach        (effective-reach band hail)
        spawn?       (effective-spawn band hail)
        match-result (matching-sessions band sessions hail)
        matches      (:sessions match-result)]
    (cond
      (:reason match-result)
      {:undeliverable {:hail hail :reason (:reason match-result)}}

      (empty? matches)
      (if (and spawn? (= :one reach))
        {:deliveries [{:hail     hail
                       :crew     (spawn-crew cfg band hail)
                       :session  nil
                       :attempts 0}]}
        {:undeliverable {:hail hail :reason :no-recipients}})

      (= :all reach)
      {:deliveries (mapv #(bound-delivery cfg band hail %) matches)}

      (= 1 (count matches))
      {:deliveries [(bound-delivery cfg band hail (first matches))]}

      :else
      {:deliveries [{:hail       hail
                     :crew       nil
                     :session    nil
                     :candidates (mapv #(candidate-entry cfg band hail %) matches)
                     :attempts   0}]})))

(defn- write-deliveries! [root fs* hail deliveries]
  (doseq [delivery deliveries]
    (let [id       (delivery-id root fs*)
          delivery (assoc delivery :id id)]
      (write-record! (delivery-path id) delivery)))
  (delete-pending! (:id hail)))

(defn- write-undeliverable! [hail record]
  (write-record! (undeliverable-path (:id hail)) record)
  (delete-pending! (:id hail)))

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
      (let [{:keys [deliveries undeliverable]}
            (resolve-obligations cfg bands sessions hail)]
        (cond
          (seq deliveries)       (write-deliveries! root fs* hail deliveries)
          undeliverable          (write-undeliverable! hail undeliverable)
          :else                  (write-undeliverable! hail {:hail hail :reason :no-recipients}))))))

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