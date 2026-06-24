(ns isaac.hail.queue
  (:require
    [clojure.edn :as edn]
    [clojure.pprint :as pprint]
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.fs :as fs]
    [isaac.hail.prepare :as prepare]
    [isaac.hail.store :as store]
    [isaac.naming :as naming]
    [isaac.tool.memory :as memory]))

(defn- write-edn [value]
  (binding [*print-namespace-maps* false]
    (with-out-str (pprint/pprint value))))

(defn- runtime-root []
  (or (loader/root)
      (root/current-root)
      (throw (ex-info "hail queue requires :root" {}))))

(defn- filesystem []
  (or (fs/instance) (throw (ex-info "hail.queue requires :fs in system" {}))))

(defn- pending-dir []
  (str (runtime-root) "/hail/pending"))

(defn- pending-path [id]
  (str (pending-dir) "/" id ".edn"))

(defn- temp-path [id]
  (str (pending-dir) "/" id ".tmp"))

(defn- naming-strategy [root fs*]
  (naming/->SequentialStrategy root "hail" "hail-" fs*))

(defn- sync-hail-counter! [root fs*]
  (let [counter-file (str root "/hail/.counter")
        max-seq      (store/max-hail-seq root fs*)
        current      (or (when (fs/exists? fs* counter-file)
                           (some-> (fs/slurp fs* counter-file) str/trim parse-long))
                         0)]
    (when (< current max-seq)
      (fs/mkdirs fs* (str root "/hail"))
      (fs/spit fs* counter-file (str max-seq)))))

(defn- next-id [root fs*]
  (sync-hail-counter! root fs*)
  (naming/generate (naming-strategy root fs*)))

(defn- snapshot-config []
  (or (when-let [root (or (loader/root) (root/current-root))]
        (some-> (loader/load-config-result {:root root :fs (filesystem)})
                :config))
      (loader/snapshot "hail queue send")
      {}))

(defn- read-record [path]
  (let [fs* (filesystem)]
    (when (fs/exists? fs* path)
      (let [record (edn/read-string (fs/slurp fs* path))]
        (if (map? record)
          (into {} (map (fn [[k v]] [(if (keyword? k) k (keyword k)) v]) record))
          record)))))

(defn- finalize-record [record root fs*]
  (let [id (-> record
               (dissoc :id :sent-at)
               (assoc :id (next-id root fs*))
               prepare/default-thread-id
               (assoc :sent-at (str (memory/now))))]
    id))

(defn send! [record]
  (let [fs*    (filesystem)
        root   (runtime-root)
        cfg    (snapshot-config)
        record (-> record
                   (dissoc :id :sent-at)
                   (prepare/enrich cfg)
                   (finalize-record root fs*))
        path   (pending-path (:id record))
        temp   (temp-path (:id record))]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit fs* temp (write-edn record))
    (fs/move fs* temp path)
    record))

(defn read-pending [id]
  (read-record (pending-path id)))