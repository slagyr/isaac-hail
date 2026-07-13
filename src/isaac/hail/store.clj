(ns isaac.hail.store
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.session.store.spi :as session-store]))

(def hail-subdirs
  "Subdirectories under <root>/hail/ scanned by find-by-id."
  ["pending" "deliveries" "delivered" "failed" "undeliverable" "broadcasts"])

(def ^:private subdir->lifecycle
  {"pending"        :pending
   "deliveries"     :in-flight
   "delivered"      :delivered
   "failed"         :failed
   "undeliverable"  :undeliverable
   "broadcasts"     :broadcast})

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

(defn- normalize-id [value]
  (cond
    (keyword? value) (name value)
    (string? value)  value
    (nil? value)     nil
    :else            (str value)))

(defn- delivery-from-turn-marker [marker]
  (or (:delivery marker)
      (when (= :hail (:source marker))
        (let [delivery-id (some-> (:delivery-id marker) str)]
          (when delivery-id
            (cond-> {:id delivery-id}
              (:prompt marker)        (assoc :prompt (:prompt marker))
              (:crew marker)          (assoc :crew (:crew marker))
              (:bound-session marker)  (assoc :bound-session (:bound-session marker))
              (:attempts marker)      (assoc :attempts (:attempts marker))
              (:thread-id marker)     (assoc :thread-id (:thread-id marker))
              (:params marker)         (assoc :params (:params marker))))))))

(defn- find-in-flight-by-id [id]
  (when-let [store (session-store/registered-store)]
    (some (fn [marker]
            (when (= id (normalize-id (:delivery-id marker)))
              (delivery-from-turn-marker marker)))
          (session-store/turn-markers store))))

(defn find-by-id-with-lifecycle
  "Locate a hail/delivery record by id across hail stores and in-flight turn markers.
   Returns {:record hail-map :lifecycle keyword} or nil."
  [id]
  (or (some (fn [subdir]
              (when-let [record (read-record (hail-path subdir id))]
                {:record    record
                 :lifecycle (subdir->lifecycle subdir)}))
            hail-subdirs)
      (when-let [record (find-in-flight-by-id id)]
        {:record record :lifecycle :in-flight})))

(defn find-by-id
  "Locate a hail or delivery record by :id across hail subdirectories and in-flight markers."
  [id]
  (:record (find-by-id-with-lifecycle id)))

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