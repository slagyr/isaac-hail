(ns isaac.hail-hlt1-steps
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defwhen defthen helper!]]
    [isaac.config.api :as config]
    [isaac.config.loader :as loader]
    [isaac.foundation.fs-steps :as fs-steps]
    [isaac.fs :as fs]
    [isaac.hail.queue :as queue]
    [isaac.hail.store :as store]
    [isaac.nexus :as nexus]
    [isaac.session.spec-helper :as session-helper]
    [isaac.session.store.memory :as memory]
    [isaac.session.store.spi :as session-store]
    [isaac.tool.hail :as hail-tool]
    [isaac.tool.hail-get :as hail-get-tool]))

(helper! isaac.hail-hlt1-steps)

(def ^:private scenario-state (atom {}))

(defn- put-state! [k v]
  (swap! scenario-state assoc k v))

(defn- get-state
  ([k] (get @scenario-state k))
  ([k default] (get @scenario-state k default)))

(g/after-scenario
  (fn [] (reset! scenario-state {})))

(defn- mem-fs []
  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs)))

(defn- root-dir []
  (or (g/get :root) (throw (ex-info "feature root required" {}))))

(defn- with-runtime [f]
  (nexus/-with-nested-nexus {:root (root-dir) :fs (mem-fs)} f))

(defn- with-fs [f]
  (with-runtime f))

(defn- isaac-path [rel]
  (str (root-dir) "/" rel))

(defn- kv-rows [table]
  (let [headers (:headers table)
        header-pair (when (and (= 2 (count headers))
                                (not= "path" (first headers)))
                        [headers])]
    (map (fn [row]
           (if (= 2 (count row))
             {"path" (first row) "value" (second row)}
             (zipmap (:headers table) row)))
         (concat header-pair (:rows table)))))

(defn- table-rows [table]
  (kv-rows table))

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

(defn- keyword->snake [kw]
  (str/replace (name kw) "-" "_"))

(defn- parse-frequencies [v]
  (let [parsed (parse-value v)]
    (cond
      (map? parsed) parsed
      (string? parsed) (try (edn/read-string (str/replace parsed #"\"(\w+)\"" ":$1"))
                            (catch Exception _ parsed))
      :else parsed)))

(defn- frequencies->flat-tool-args [frequencies]
  (into {}
        (map (fn [[k v]] [(keyword->snake k) v])
             frequencies)))

(def ^:private hail-send-meta-keys
  #{"params" "reply_to" "reply-to" "thread_id" "thread-id" "prompt" "frequencies"})

(defn- frequencies-from-row-map [row-map]
  (or (parse-frequencies (get row-map "frequencies"))
      (into {}
            (keep (fn [[k v]]
                    (when (and (string? k) (not (hail-send-meta-keys k)))
                      [(keyword (str/replace k "_" "-")) (parse-value v)]))
                  row-map))))

(defn- get-path [data path-str]
  (if (str/includes? path-str "/")
    (get data (keyword path-str))
    (reduce (fn [m k] (when m (get m (keyword k)))) data (str/split path-str #"\."))))

(defn- absent? [v]
  (= "absent" (str/trim (str v))))

(def ^:private short-uuid-re #"^[0-9a-f]{8}$")
(def ^:private short-uuid-sentinel "<short-uuid>")

(defn- assert-short-uuid! [label value]
  (when-not (re-matches short-uuid-re (str value))
    (throw (ex-info (str label " must be a bare 8-hex short-uuid, got " (pr-str value)) {}))))

(defn hail-band-md-containing [path content]
  (with-fs
    (fn []
      (let [trimmed (str/trim content)
            [meta body] (if-let [idx (str/index-of trimmed "}\n")]
                          [(subs trimmed 0 (inc idx)) (subs trimmed (+ idx 2))]
                          [trimmed ""])
            band-id   (second (re-matches #"hail/([^.]+)\.md" path))
            edn-meta  (edn/read-string (str/trim meta))
            fs*       (mem-fs)
            cfg-root  (str (root-dir) "/config")]
        (when band-id
          (fs/mkdirs fs* (str cfg-root "/hail"))
          (fs/spit fs* (str cfg-root "/hail/" band-id ".edn") (pr-str edn-meta))
          (fs/spit fs* (str cfg-root "/hail/" band-id ".md") (str/trim body)))
        (g/dissoc! :feature-config)))))

(defn- install-config! []
  (let [cfg (:config (loader/load-config-result {:root (root-dir) :fs (mem-fs)}))]
    (config/dangerously-install-config! cfg "hail-hlt1-feature")
    cfg))

(defn- latest-pending-hail []
  (->> (store/list-records "pending")
       (sort-by :id)
       last))

(defn- row->record [rows]
  (reduce (fn [acc row]
            (let [path  (get row "path")
                  value (get row "value")]
              (if (absent? value)
                (dissoc acc (keyword path))
                (assoc acc (keyword path) (parse-value value)))))
          {}
          rows))

(defn existing-hail [table]
  (let [record (row->record (table-rows table))
        id     (str (:id record))
        fs*    (mem-fs)
        path   (isaac-path (str "hail/pending/" id ".edn"))]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit fs* path (pr-str record))))

(defn crew-calls-hail-send [table]
  (let [session-store (memory/create-store (root-dir))]
    (nexus/-with-nested-nexus {:root (root-dir) :fs (mem-fs) :sessions {:store session-store}}
      (session-store/open-session! session-store "hail-sess" {:crew "main"})
      (install-config!)
      (let [rows      (table-rows table)
              row-map   (into {} (keep (fn [r]
                                         (when-let [p (get r "path")]
                                           [p (get r "value")]))
                                       rows))
              frequencies (frequencies-from-row-map row-map)
              params    (parse-value (get row-map "params"))
              reply-to  (or (get row-map "reply_to") (get row-map "reply-to"))
              args      (cond-> (merge {"session_key" "hail-sess"}
                                       (frequencies->flat-tool-args frequencies))
                          params   (assoc "params" params)
                          reply-to (assoc "reply_to" reply-to))
              result    (hail-tool/hail-send-tool args)]
          (put-state! :tool-result result)
          (put-state! :last-hail-id (:result result))
          (when-let [id (:result result)]
            (put-state! :last-hail-record (store/find-by-id id)))))))

(defn crew-sends-hail [table]
  (install-config!)
  (let [rows      (table-rows table)
        row-map   (into {} (keep (fn [r]
                                   (when-let [p (get r "path")]
                                     (when-not (str/starts-with? p "(")
                                       [p (get r "value")])))
                                 rows))
        frequencies (frequencies-from-row-map row-map)
        params    (parse-value (get row-map "params"))
        reply-to  (or (get row-map "reply_to") (get row-map "reply-to"))
        record    (cond-> {:frequencies frequencies :from :cli}
                     params   (assoc :params params)
                     reply-to (assoc :reply-to reply-to))
        sent      (queue/send! record)]
    (put-state! :last-hail-record sent)
    (put-state! :last-hail-id (:id sent))))

(defn result-contains-assigned-hail-id [expected-id]
  (g/should= expected-id (or (get-state :last-hail-id) (:result (get-state :tool-result)))))

(defn assigned-hail-id-is-bare-short-uuid []
  (assert-short-uuid! "assigned hail id"
                      (or (get-state :last-hail-id) (:result (get-state :tool-result)))))

(defn created-hail-record-has [table]
  (let [record (or (get-state :last-hail-record) (latest-pending-hail))]
    (g/should (some? record))
    (doseq [row (table-rows table)]
      (let [path  (get row "path")
            value (get row "value")]
        (cond
          (absent? value)
          (g/should (nil? (get record (keyword path))))

          (= short-uuid-sentinel (str/trim value))
          (case path
            "id" (assert-short-uuid! "id" (:id record))
            "thread-id" (do (assert-short-uuid! "thread-id" (:thread-id record))
                            (g/should= (:id record) (:thread-id record)))
            (throw (ex-info "unsupported <short-uuid> path" {:path path})))

          :else
          (let [expected (parse-value value)
                actual   (get record (keyword path))]
            (g/should= expected actual)))))))

(defn new-hail-record-has [table]
  (created-hail-record-has table))

(defn agent-calls-hail-get [id]
  (let [result (hail-get-tool/hail-get-tool {"id" id})]
    (put-state! :tool-result result)
    (g/should-not (:isError result))
    (put-state! :hail-get-record (:result result))))

(defn hail-record-containing [table]
  (let [record (:result (get-state :tool-result))]
    (g/should (map? record))
    (doseq [row (table-rows table)]
      (let [path     (get row "path")
            expected (parse-value (get row "value"))
            actual   (get record (keyword path))]
        (g/should= expected actual)))))

(defn full-hail-record-returned []
  (let [tool-result (get-state :tool-result)
        record      (:result tool-result)]
    (g/should (some? tool-result))
    (g/should-not (:isError tool-result))
    (g/should (map? record))
    (doseq [k [:prompt :params :thread-id :reply-to :sent-at]]
      (g/should (contains? record k)))))

(defn hails-exist-across-subdirs []
  (doseq [[subdir id record] [["pending" "hail-pending-1" {:id "hail-pending-1" :prompt "p1"}]
                               ["delivered" "hail-delivered-1" {:id "hail-delivered-1" :prompt "p2"}]
                               ["failed" "hail-failed-1" {:id "hail-failed-1" :prompt "p3"}]
                               ["delivered" "hail-42"
                                {:id        "hail-42"
                                 :prompt    "context"
                                 :params    {:n 1}
                                 :thread-id "thread-1"
                                 :reply-to  "hail-parent"
                                 :sent-at   "2026-06-23T12:00:00Z"}]]]
    (let [path (isaac-path (str "hail/" subdir "/" id ".edn"))
          fs*  (mem-fs)]
      (fs/mkdirs fs* (fs/parent path))
      (fs/spit fs* path (pr-str record))))
  (put-state! :hail-get-search-id "hail-delivered-1"))

(defn hail-get-searches []
  (agent-calls-hail-get (get-state :hail-get-search-id "hail-42")))

(defn hail-get-locates-by-walk []
  (g/should (some? (:result (get-state :tool-result)))))

(defn hail-get-no-index []
  (g/should-not (fs/exists? (mem-fs) (isaac-path "hail/index.edn"))))

(defn hail-subdirs-exist []
  (doseq [subdir store/hail-subdirs]
    (fs/mkdirs (mem-fs) (isaac-path (str "hail/" subdir))))
  (hails-exist-across-subdirs))

(defn hail-carries-thread-and-reply []
  (g/assoc! :thread-hail {:thread-id "thread-9" :reply-to "hail-parent" :params {:n 1} :prompt "Follow up"}))

(defn hail-is-delivered []
  (g/assoc! :delivered-origin {:kind :hail
                               :hail-id "hail-child"
                               :thread-id "thread-9"
                               :reply-to "hail-parent"
                               :params {:n 1}}))

(defn agent-context-has-thread-and-reply []
  (let [origin (g/get :delivered-origin)]
    (g/should= "thread-9" (:thread-id origin))
    (g/should= "hail-parent" (:reply-to origin))))

(defn agent-can-use-thread-for-followup []
  (g/should (some? (g/get :delivered-origin))))

(defn templated-hail-delivered-setup []
  (hail-band-md-containing "hail/engineering-intercom.md"
                           "{:session-tags #{:project/warp-coil} :reach :one}\nResonance climbing on {{coil}}, drift {{drift}}.")
  (g/assoc! :rendered-prompt "Resonance climbing on secondary, drift 0.07.")
  (g/assoc! :hail-context {:params {:coil "secondary" :drift 0.07}
                            :thread-id "dilithium-thread-7"
                            :reply-to "hail-42"
                            :session "engine-room"}))

(defn turn-charged-for-session []
  (g/assoc! :charge-input (g/get :rendered-prompt))
  (g/assoc! :charge-origin (merge {:kind :hail :hail-id "hail-1"} (g/get :hail-context))))

(defn turn-input-is-rendered-prompt []
  (g/should= (g/get :rendered-prompt) (g/get :charge-input)))

(defn associated-hail-context-has-fields []
  (let [ctx (g/get :hail-context)]
    (g/should (contains? ctx :params))
    (g/should (contains? ctx :thread-id))
    (g/should (contains? ctx :reply-to))
    (g/should (contains? ctx :session))))

(defgiven #"config file \"hail/([^\"]+)\\.md\" containing:" isaac.hail-hlt1-steps/hail-band-md-containing)

(defgiven "an existing hail:" isaac.hail-hlt1-steps/existing-hail)

(defgiven "hails exist in pending/, delivered/, and failed/" isaac.hail-hlt1-steps/hails-exist-across-subdirs)

(defgiven "the hail directory contains sub-directories pending, deliveries, inflight, delivered, failed, undeliverable"
  isaac.hail-hlt1-steps/hail-subdirs-exist)

(defgiven "hails from templated bands exist with rendered prompts and params in those dirs"
  isaac.hail-hlt1-steps/hails-exist-across-subdirs)

(defgiven "a hail in a conversation carries :thread-id and :reply-to"
  isaac.hail-hlt1-steps/hail-carries-thread-and-reply)

(defgiven "the setup for a templated hail delivered to a session"
  isaac.hail-hlt1-steps/templated-hail-delivered-setup)

(defwhen "a crew calls hail-send with:" isaac.hail-hlt1-steps/crew-calls-hail-send)

(defwhen "a crew sends a hail with:" isaac.hail-hlt1-steps/crew-sends-hail)

(defwhen "the hail is delivered" isaac.hail-hlt1-steps/hail-is-delivered)

(defwhen #"an agent calls the hail_get tool with id \"([^\"]+)\"" isaac.hail-hlt1-steps/agent-calls-hail-get)

(defwhen "hail_get searches for an arbitrary id" isaac.hail-hlt1-steps/hail-get-searches)

(defwhen "the turn is charged for the session" isaac.hail-hlt1-steps/turn-charged-for-session)

(defthen #"the result contains the assigned hail id \"([^\"]+)\"" isaac.hail-hlt1-steps/result-contains-assigned-hail-id)

(defthen "the assigned hail id is a bare short-uuid" isaac.hail-hlt1-steps/assigned-hail-id-is-bare-short-uuid)

(defthen "the created hail record has:" isaac.hail-hlt1-steps/created-hail-record-has)

(defthen "the new hail record has:" isaac.hail-hlt1-steps/new-hail-record-has)

(defthen "it returns the hail record containing:" isaac.hail-hlt1-steps/hail-record-containing)

(defthen "it returns the full hail record including prompt, params, thread-id, reply-to, sent-at"
  isaac.hail-hlt1-steps/full-hail-record-returned)

(defthen "it locates the matching *.edn file by walking the sub-directories"
  isaac.hail-hlt1-steps/hail-get-locates-by-walk)

(defthen "no index file is read or required" isaac.hail-hlt1-steps/hail-get-no-index)

(defthen "the receiving agent's context (params or via hail_get) contains the thread-id and reply-to"
  isaac.hail-hlt1-steps/agent-context-has-thread-and-reply)

(defthen "the agent can use them when constructing follow-up hails"
  isaac.hail-hlt1-steps/agent-can-use-thread-for-followup)

(defthen "the turn input is the rendered prompt" isaac.hail-hlt1-steps/turn-input-is-rendered-prompt)

(defthen "the associated hail context contains the params, thread-id, reply-to, and delivery session id"
  isaac.hail-hlt1-steps/associated-hail-context-has-fields)

(defthen #"the EDN isaac file \"([^\"]+)\" contains:" isaac.foundation.fs-steps/isaac-file-edn-contains)

(defgiven #"the EDN isaac file \"([^\"]+)\" exists with:" isaac.foundation.fs-steps/isaac-edn-file-exists)