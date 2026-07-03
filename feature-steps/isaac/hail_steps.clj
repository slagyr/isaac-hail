(ns isaac.hail-steps
  "Hail-specific gherclj steps ported from the monolith server-steps hail
   section, plus clock binding for deterministic CLI timestamps."
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defwhen defthen helper!]]
    [isaac.agent.config.runtime :as agent-runtime]
    [isaac.config.api :as config]
    [isaac.config.loader :as loader]
    [isaac.foundation.cli-steps :as fcli]
    [isaac.fs :as fs]
    [isaac.hail.delivery-worker :as hail-delivery-worker]
    [isaac.hail.router :as hail-router]
    [isaac.llm.api.grover :as grover]
    [isaac.hail.store :as store]
    [isaac.logger :as log]
    [isaac.module.loader :as module-loader]
    [isaac.nexus :as nexus]
    [isaac.server.server-steps :as server-steps]
    [isaac.session.store.spi :as session-store]
    [isaac.tool.memory :as memory]))

(helper! isaac.hail-steps)

(def ^:private short-uuid-re #"^[0-9a-f]{8}$")
(def ^:private short-uuid-sentinel "<short-uuid>")

(fcli/register-isaac-run-wrapper!
  (fn [thunk]
    (if-let [ct (g/get :current-time)]
      (binding [memory/*now* ct] (thunk))
      (thunk))))

(defn- server-fs []
  (or (g/get :mem-fs) (fs/real-fs)))

(defn- with-server-fs [f]
  (let [fs* (server-fs)]
    (nexus/-with-nested-nexus {:fs fs*}
      (f))))

(defn- runtime-root-dir []
  (or (g/get :runtime-root-dir) (g/get :root)))

(defn- deep-merge [a b]
  (if (and (map? a) (map? b))
    (merge-with deep-merge a b)
    (or b a)))

(defn- load-server-config [root fs*]
  (let [load!       #(:config (loader/load-config-result {:root root :fs fs*}))
        entity-dir? #(with-server-fs
                       (fn []
                         (seq (fs/children fs* (str root "/config/" %)))))
        cfg         (load!)]
    (if (and (or (entity-dir? "crew") (entity-dir? "models") (entity-dir? "providers"))
             (empty? (or (:crew cfg) {}))
             (empty? (or (:models cfg) {}))
             (empty? (or (:providers cfg) {})))
      (load!)
      cfg)))

(defn- current-server-config []
  (let [home     (g/get :root)
        fs*      (server-fs)
        base     (with-server-fs #(load-server-config home fs*))
        merged   (deep-merge base
                             (merge (or (g/get :server-config) {})
                                    (when-let [providers (g/get :provider-configs)]
                                      {:providers providers})))
        runtime  (runtime-root-dir)
        disc     (nexus/-with-nested-nexus {:fs fs*}
                   (module-loader/discover! merged {:root runtime
                                                    :cwd  (System/getProperty "user.dir")}))]
    (assoc merged
           :module-index (:index disc)
           :root runtime)))

(defn- record-turn-future! [futures]
  (when-let [future* (first futures)]
    (g/assoc! :turn-future future*)))

(defn- feature-session-store [root cfg]
  (or (session-store/registered-store)
      (do (agent-runtime/install! {:config cfg})
          (session-store/registered-store))))

(defn- pending-hails []
  (->> (with-server-fs #(store/list-records "pending"))
       (sort-by (juxt :sent-at :id))))

(defn- assert-short-uuid! [label value]
  (when-not (re-matches short-uuid-re (str value))
    (throw (ex-info (str label " must be a bare 8-hex short-uuid, got " (pr-str value)) {}))))

(defn- table-row-map [table]
  (map (fn [row]
         (zipmap (:headers table) row))
       (:rows table)))

(defn- edn-looking? [s]
  (or (str/starts-with? s "{")
      (str/starts-with? s "[")
      (str/starts-with? s ":")
      (str/starts-with? s "\"")
      (str/starts-with? s "#")
      (re-matches #"-?\d+" s)
      (= "true" (str/lower-case s))
      (= "false" (str/lower-case s))))

(defn- parse-value [v]
  (if (string? v)
    (let [s (str/trim v)]
      (if (edn-looking? s)
        (try (edn/read-string s) (catch Exception _ s))
        s))
    v))

(defn- assert-pending-record-fields [record table]
  (doseq [row (table-row-map table)]
    (let [path  (get row "path")
          value (get row "value")]
      (cond
        (= short-uuid-sentinel (str/trim value))
        (case path
          "id" (assert-short-uuid! "id" (:id record))
          "thread-id" (do (assert-short-uuid! "thread-id" (:thread-id record))
                          (g/should= (:id record) (:thread-id record)))
          (throw (ex-info (str "unsupported <short-uuid> path: " path) {:path path})))

        :else
        (g/should= (parse-value value) (get record (keyword path)))))))

(defn sole-pending-hail-edn-contains [table]
  (let [records (pending-hails)]
    (g/should= 1 (count records))
    (assert-pending-record-fields (first records) table)))

(defn pending-hail-edn-contains [n table]
  (let [idx     (dec (if (string? n) (parse-long n) n))
        records (pending-hails)]
    (when-not (<= (inc idx) (count records))
      (throw (ex-info (str "expected at least " n " pending hails, found " (count records)) {})))
    (assert-pending-record-fields (nth records idx) table)))

(defn pending-hail-edn-does-not-contain [n table]
  (let [idx     (dec (if (string? n) (parse-long n) n))
        record  (nth (pending-hails) idx)]
    (doseq [row (table-row-map table)]
      (g/should-not (contains? record (keyword (get row "path")))))))

(defn pending-hail-ids-are-distinct []
  (let [ids (map :id (pending-hails))]
    (g/should= (count ids) (count (set ids)))))

(defn- delivery-records []
  (with-server-fs #(store/list-records "deliveries")))

(defn- broadcast-record [parent-id]
  (first (filter #(= parent-id (:id %))
                 (with-server-fs #(store/list-records "broadcasts")))))

(defn broadcast-children-are-distinct-short-uuids [parent-id]
  (let [record   (broadcast-record parent-id)
        children (:children record)]
    (when-not (some? record)
      (throw (ex-info (str "broadcast parent " parent-id " not found") {})))
    (when-not (pos? (count children))
      (throw (ex-info "broadcast parent has no children" {})))
    (doseq [id children]
      (assert-short-uuid! "broadcast child" id))
    (g/should= (count children) (count (set children)))))

(defn child-delivery-for-session-edn-contains [session table]
  (let [session-kw (keyword session)
        matches    (filter #(= session-kw (:bound-session %)) (delivery-records))]
    (when-not (= 1 (count matches))
      (throw (ex-info (str "expected one delivery for session " session
                           ", found " (count matches)) {})))
    (assert-pending-record-fields (first matches) table)))

(defn delivery-hail-count-is [n]
  (let [expected (if (string? n) (parse-long n) n)
        actual   (count (delivery-records))]
    (g/should= expected actual)))

(defn stdout-is-bare-hail-id []
  (let [output (str/trim (or (g/get :output) ""))]
    (assert-short-uuid! "stdout" output)))

(defn stdout-json-hail-id-is-bare-short-uuid []
  (let [value (json/parse-string (str/trim (or (g/get :output) "")) true)]
    (assert-short-uuid! "stdout JSON id" (:id value))))

(defn stdout-edn-hail-id-is-bare-short-uuid []
  (let [value (edn/read-string (str/trim (or (g/get :output) "")))]
    (assert-short-uuid! "stdout EDN id" (:id value))))

(defn hail-router-ticks []
  (g/assoc! :runtime-root-dir (g/get :root))
  (with-server-fs
    (fn []
      (let [fs*           (server-fs)
            cfg           (current-server-config)
            root          (runtime-root-dir)
            session-store (feature-session-store root cfg)]
        (nexus/-with-nexus {:config   (atom cfg)
                            :root     root
                            :fs       fs*
                            :sessions {:store session-store}}
          (config/dangerously-install-config! cfg "spec")
          (hail-router/tick! {:cfg cfg :session-store session-store}))))))

(defn hail-delivery-worker-ticks []
  (log/set-output! :memory)
  (log/clear-entries!)
  (g/assoc! :runtime-root-dir (g/get :root))
  (with-server-fs
    (fn []
      (let [fs*           (server-fs)
            cfg           (current-server-config)
            root          (runtime-root-dir)
            session-store (feature-session-store root cfg)]
        (nexus/-with-nexus {:config   (atom cfg)
                            :root     root
                            :fs       fs*
                            :sessions {:store session-store}}
          (config/dangerously-install-config! cfg "spec")
          (record-turn-future! (hail-delivery-worker/tick! {:cfg cfg :session-store session-store})))))))

(defn hail-delivery-worker-ticks-at [iso]
  (log/set-output! :memory)
  (log/clear-entries!)
  (g/assoc! :runtime-root-dir (g/get :root))
  (with-server-fs
    (fn []
      (let [fs*           (server-fs)
            cfg           (current-server-config)
            root          (runtime-root-dir)
            session-store (feature-session-store root cfg)]
        (nexus/-with-nexus {:config   (atom cfg)
                            :root     root
                            :fs       fs*
                            :sessions {:store session-store}}
          (config/dangerously-install-config! cfg "spec")
          (record-turn-future! (hail-delivery-worker/tick! {:cfg           cfg
                                                            :now           (java.time.Instant/parse iso)
                                                            :session-store session-store})))))))

(defn isaac-system-started []
  (server-steps/server-running))

;; region ----- Hail delivery system preamble -----

(defn- compile-pattern [cell]
  (let [s (str/trim cell)]
    (if (and (str/starts-with? s "#\"") (str/ends-with? s "\""))
      (re-pattern (subs s 2 (dec (count s))))
      (re-pattern (java.util.regex.Pattern/quote s)))))

(defn- message-text [msg]
  (let [c (:content msg)]
    (cond
      (string? c)     c
      (sequential? c) (str/join "\n" (keep #(or (:text %) (get % "text")) c))
      :else           (str c))))

(defn- hail-turn-preamble []
  ;; The origin+autonomy system preamble is framed (as a trusted block) into
  ;; the current/last user message of the delivered turn's request. The hail
  ;; worker runs its own future, so read the fake provider's last request
  ;; directly (mirrors session-steps' last-llm-request fallback).
  (let [msgs (:messages (or (g/get :llm-request) (grover/last-request)))]
    (message-text (last (filter #(= "user" (:role %)) msgs)))))

(defn hail-turn-system-preamble-matching [_session table]
  (let [text (hail-turn-preamble)]
    (doseq [row (:rows table)]
      (let [pat (compile-pattern (first row))]
        (g/should (re-find pat text))))))

(defn hail-turn-system-preamble-not-matching [_session table]
  (let [text (hail-turn-preamble)]
    (doseq [row (:rows table)]
      (let [pat (compile-pattern (first row))]
        (g/should-not (re-find pat text))))))

(defthen #"the hail turn on session \"([^\"]+)\" has a system preamble matching:"
  isaac.hail-steps/hail-turn-system-preamble-matching)

(defthen #"the hail turn on session \"([^\"]+)\" has a system preamble not matching:"
  isaac.hail-steps/hail-turn-system-preamble-not-matching)

;; endregion ^^^^^ Hail delivery system preamble ^^^^^

(defwhen "the hail router ticks" isaac.hail-steps/hail-router-ticks)

(defwhen "the hail delivery worker ticks" isaac.hail-steps/hail-delivery-worker-ticks)

(defwhen #"the hail delivery worker ticks at \"([^\"]+)\"" isaac.hail-steps/hail-delivery-worker-ticks-at)

(defwhen "the Isaac system is started" isaac.hail-steps/isaac-system-started)

(defthen "the sole pending hail EDN contains:" isaac.hail-steps/sole-pending-hail-edn-contains)

(defthen #"pending hail (\d+) EDN contains:" isaac.hail-steps/pending-hail-edn-contains)

(defthen #"pending hail (\d+) EDN does not contain:" isaac.hail-steps/pending-hail-edn-does-not-contain)

(defthen "pending hail ids are distinct" isaac.hail-steps/pending-hail-ids-are-distinct)

(defthen "the stdout is a bare hail id" isaac.hail-steps/stdout-is-bare-hail-id)

(defthen "the stdout JSON hail id is a bare short-uuid" isaac.hail-steps/stdout-json-hail-id-is-bare-short-uuid)

(defthen "the stdout EDN hail id is a bare short-uuid" isaac.hail-steps/stdout-edn-hail-id-is-bare-short-uuid)

(defthen #"broadcast \"([^\"]+)\" children are distinct bare short-uuids"
  isaac.hail-steps/broadcast-children-are-distinct-short-uuids)

(defthen #"child delivery for session ([^ ]+) EDN contains:"
  isaac.hail-steps/child-delivery-for-session-edn-contains)

(defthen #"delivery hail count is (\d+)" isaac.hail-steps/delivery-hail-count-is)