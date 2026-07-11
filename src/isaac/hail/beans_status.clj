(ns isaac.hail.beans-status
  (:require
    [clojure.java.shell :as shell]
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]))

(defn bean-id
  [delivery]
  (when-let [raw (or (get-in delivery [:params :bean-id])
                      (get-in delivery [:params "bean-id"])
                      (get-in delivery [:data :bean-id])
                      (get-in delivery [:data "bean-id"]))]
    (let [s (str/trim (str raw))]
      (when (not (str/blank? s)) s))))

(defn- beans-repo-key [delivery]
  (when-let [raw (or (get-in delivery [:data :bean-repo])
                      (get-in delivery [:data "bean-repo"]))]
    (let [s (str/trim (str raw))]
      (when (not (str/blank? s)) s))))

(defn- resolve-beans-dir [cfg delivery]
  (when-let [repo (beans-repo-key delivery)]
    (or (get-in cfg [:hail-settings :beans-repos (keyword repo)])
        (get-in cfg [:hail-settings :beans-repos repo])
        (when (= repo "isaac")
          (str (System/getProperty "user.home") "/agents/isaac/work-1/isaac"))
        (when-let [root (or (:beans-root cfg) (get-in cfg [:hail-settings :beans-root]))]
          (if (str/ends-with? root "/") (str root repo) (str root "/" repo))))))

(defn- bean-markdown-path [beans-dir bean-id*]
  (let [dir (str beans-dir "/.beans")]
    (try
      (some (fn [^java.io.File f]
              (let [n (.getName f)]
                (when (and (str/starts-with? n (str bean-id* "--")) (str/ends-with? n ".md"))
                  (.getPath f))))
            (.listFiles (java.io.File. dir)))
      (catch Exception _ nil))))

(defn- status-from-front-matter [text]
  (some (fn [line]
          (when (str/starts-with? line "status:")
            (str/trim (subs line (count "status:")))))
        (str/split-lines (or text ""))))

(defn- read-status-from-file [beans-dir bean-id*]
  (when-let [path (bean-markdown-path beans-dir bean-id*)]
    (let [fs* (or (nexus/get :fs) fs/instance)]
      (when (fs/exists? fs* path)
        (status-from-front-matter (fs/slurp fs* path))))))

(defn- parse-beans-show-status [output]
  (some (fn [line]
          (let [parts (str/split (str/trim line) #"\s{2,}")]
            (when (and (>= (count parts) 2) (re-matches #"isaac-[a-z0-9]+" (first parts)))
              (second parts))))
        (str/split-lines (or output ""))))

(defn- beans-show-status [beans-dir bean-id*]
  (try
    (let [{:keys [out exit]} (shell/sh "beans" "show" bean-id* :dir beans-dir)]
      (when (zero? (long exit)) (parse-beans-show-status out)))
    (catch Exception e
      (log/debug :hail/beans-show-failed :bean-id bean-id* :error (.getMessage e))
      nil)))

(defn refresh-beans-repo!
  [beans-dir]
  (when (and beans-dir (.exists (java.io.File. (str beans-dir "/.git"))))
    (try
      (let [{:keys [exit]} (shell/sh "git" "-C" beans-dir "pull" "--rebase")]
        (when (pos? (long exit)) (log/debug :hail/beans-pull-nonzero :dir beans-dir)))
      (catch Exception e
        (log/debug :hail/beans-pull-failed :dir beans-dir :error (.getMessage e))))))

(defn bean-status [cfg delivery bean-id*]
  (when-let [dir (resolve-beans-dir cfg delivery)]
    (refresh-beans-repo! dir)
    (or (beans-show-status dir bean-id*) (read-status-from-file dir bean-id*))))

(defn bean-completed? [cfg delivery bean-id*]
  (= "completed" (bean-status cfg delivery bean-id*)))

(defn turn-in-limbo? [cfg delivery result]
  (when-let [id (bean-id delivery)]
    (and (not (contains? (:executed-tool-names result #{}) "hail-send"))
         (not (bean-completed? cfg delivery id)))))
