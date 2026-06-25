(ns isaac.hail.store
  (:require
    [clojure.edn :as edn]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]))

(def hail-subdirs
  "Subdirectories under <root>/hail/ scanned by find-by-id."
  ["pending" "deliveries" "inflight" "delivered" "failed" "undeliverable" "broadcasts"])

(defn- runtime-root []
  (or (loader/root) (throw (ex-info "hail store requires :root" {}))))

(defn- filesystem []
  (or (fs/instance) (throw (ex-info "hail.store requires :fs in system" {}))))

(defn- normalize-record [record]
  (if (map? record)
    (into {} (map (fn [[k v]] [(if (keyword? k) k (keyword k)) v]) record))
    record))

(defn read-record [path]
  (let [fs* (filesystem)]
    (when (fs/exists? fs* path)
      (normalize-record (edn/read-string (fs/slurp fs* path))))))

(defn- hail-path [subdir id]
  (str (runtime-root) "/hail/" subdir "/" id ".edn"))

(defn find-by-id
  "Locate a hail or delivery record by :id across hail subdirectories."
  [id]
  (some #(read-record (hail-path % id)) hail-subdirs))

(defn- hail-seq-from-filename [filename]
  (some-> (re-matches #"hail-(\d+)\.edn" filename) second parse-long))

(defn max-hail-seq
  "Highest hail-N sequence number found across hail subdirectories."
  ([]
   (max-hail-seq (runtime-root) (filesystem)))
  ([root fs*]
   (apply max 0
          (for [subdir hail-subdirs
                :let [dir (str root "/hail/" subdir)]
                child (or (fs/children fs* dir) [])
                :let [n (hail-seq-from-filename child)]
                :when n]
            n))))

(defn list-records [subdir]
  (let [fs* (filesystem)
        dir (str (runtime-root) "/hail/" subdir)]
    (if-let [children (fs/children fs* dir)]
      (->> children
           (map #(read-record (str dir "/" %)))
           (remove nil?)
           vec)
      [])))