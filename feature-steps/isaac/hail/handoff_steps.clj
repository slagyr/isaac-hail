(ns isaac.hail.handoff-steps
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defthen defwhen helper!]]
    [isaac.foundation.cli-steps :as cli-steps]
    [isaac.http.server-steps :as http-steps]
    [isaac.foundation.cli.host :as host]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus]
    [isaac.foundation.config.loader :as loader]
    [isaac.foundation.config.api :as config]
    [isaac.agent.tool.memory]
    [isaac.hail.tool :as hail-tool]
    [isaac.agent.session.session-steps :as session-steps]
    [isaac.agent.session.store.spi :as session-store]
    [isaac.agent.step-tables :as tables]
    [isaac.agent.turn.queue :as turns]
    [isaac.agent.turn.worker :as worker]))

(helper! isaac.hail.handoff-steps)

(defn- turn-id []
  (or (some->> (g/get :channel-events)
               deref
               (filter #(and (= "tool-result" (:event %))
                             (= "hail-send" (get-in % [:tool :name]))))
               last :result)
      (some-> (g/get :tool-result) :result)
      (some-> (g/get :http-response) :body
              ((fn [body] (if (str/starts-with? body "{")
                            (try (:id (json/parse-string body true))
                                 (catch Exception _ (:id (edn/read-string body))))
                            nil))))
      (some-> (g/get :output) str/trim (str/split #"\s+") first)))

(defn- interpolate-id [s]
  (str/replace s #"#([\w-]+-id)" (fn [[_ name]] (or (g/get (keyword name)) (str "#" name)))))

(cli-steps/register-isaac-run-wrapper!
  (fn [thunk]
    (let [cfg (:config (loader/load-config-result {:root (g/get :root)
                                                    :fs (or (g/get :mem-fs) (fs/real-fs))}))]
      (with-redefs [loader/snapshot (fn [_] cfg)]
        (if-let [ct (g/get :current-time)]
          (binding [isaac.agent.tool.memory/*now* ct] (thunk))
          (thunk))))))

(alter-var-root #'cli-steps/isaac-run
  (fn [original]
    (fn [args] (original (interpolate-id args)))))

(defn- record []
  (nexus/-with-nested-nexus {:root (g/get :root) :fs (g/get :mem-fs)}
    (turns/read-held (turn-id))))

(defn- path-value [data path]
  (tables/get-path data path))

(defn- expected [value]
  (let [s (str/trim value)]
    (cond
      (str/blank? s) nil
      (re-matches #"-?\d+" s) (parse-long s)
      (= "#turn-id" s) (turn-id)
      (re-matches #"#[\w-]+-id" s) (g/get (keyword (subs s 1)))
      (and (str/starts-with? s "#\"") (str/ends-with? s "\"")) (re-pattern (subs s 2 (dec (count s))))
      (some #(str/starts-with? s %) [":" "#{" "[" "{" "\""]) (edn/read-string s)
      :else s)))

(defn stdout-matches-and-captures [table]
  (let [output (or (g/get :output) "")]
    (doseq [cell (if (seq (:rows table)) (map first (:rows table)) (:headers table))]
      (if-let [[_ pattern capture] (re-matches #"#\"(.+)\":([\w-]+)" cell)]
        (let [found (re-find (re-pattern pattern) output)]
          (g/should found)
          (when found (g/assoc! (keyword capture) found)))
        (let [pattern (if (and (str/starts-with? cell "#\"") (str/ends-with? cell "\""))
                        (re-pattern (subs cell 2 (dec (count cell))))
                        (re-pattern (java.util.regex.Pattern/quote (or (g/get (keyword (subs cell 1))) cell))))]
          (g/should (re-find pattern output)))))))

(defn submitted-turn-has [table]
  (when-not (record)
    (session-steps/await-turn!))
  (let [turn (record)]
    (g/should turn)
    (doseq [[path raw] (:rows table)]
      (let [actual (path-value turn path)
            wanted (expected raw)]
        (if (and (= path "session") (keyword? wanted) (string? actual))
          (g/should= (name wanted) actual)
          (if (instance? java.util.regex.Pattern wanted)
            (g/should (re-find wanted (str actual)))
            (g/should= wanted actual)))))))

(defn turn-queue-ticks-at [iso]
  (nexus/-with-nested-nexus {:root (g/get :root) :fs (g/get :mem-fs)}
    (let [cfg (:config (loader/load-config-result {:root (g/get :root) :fs (g/get :mem-fs)}))
          session-file (str (g/get :root) "/config/hail/warp-coil-callout.edn")
          create-fixture? (fs/exists? (g/get :mem-fs) session-file)]
      (when create-fixture?
        (config/dangerously-install-config!
          (assoc-in cfg [:sessions :naming-strategy] :sequential) "hail feature session create"))
      (worker/tick! {:now (java.time.Instant/parse (str iso "Z"))})
      ;; tick! only claims and starts each runnable record before returning
      ;; (isaac-e9jl, isaac-agent) — a long-running turn no longer blocks
      ;; another session's claimed turn from starting in the same pass, but
      ;; that also means the tick itself no longer waits for any of them to
      ;; finish. await-idle! blocks until every turn tick! just started (and
      ;; anything it chains) has actually settled, so the very next step
      ;; ("session ... has transcript matching", "the turn Hail submitted
      ;; has") sees the finished result instead of racing it.
      (worker/await-idle!))))

(defn last-hail-send-error-matches [pattern-str]
  (session-steps/await-turn!)
  (let [result (or (some->> (g/get :channel-events) deref
                            (filter #(and (= "tool-result" (:event %))
                                          (= "hail-send" (get-in % [:tool :name]))))
                            last :result)
                   (g/get :tool-result))
        error (cond
                (and (map? result) (:isError result)) (:error result)
                (and (string? result) (str/starts-with? result "Error:"))
                (str/trim (subs result (count "Error:"))))]
    (g/should (some? error))
    (g/should (re-find (re-pattern (subs pattern-str 2 (dec (count pattern-str)))) error))))

(defthen #"the last hail-send tool result is an error matching (.+)"
  isaac.hail.handoff-steps/last-hail-send-error-matches)

(defn next-isaac-command-starts-in-a-fresh-process
  "A real shell starts every isaac invocation with nothing registered. The
   Background's root setup pre-registers a session store (isaac.agent.session.
   store.spi) and the CLI host boundary (isaac.foundation.cli.host) memoizes each
   :install! fn it has already run for the lifetime of this test JVM — both
   process-wide, both invisible to a real fresh shell. Clear them so the next
   'isaac is run with' sees what a real shell sees (isaac-1i1x)."
  []
  (nexus/deregister! [:sessions])
  (reset! (.installed ^isaac.foundation.cli.host.ProcessHost host/process-host) #{}))

(defgiven "the next isaac command starts in a fresh process"
  isaac.hail.handoff-steps/next-isaac-command-starts-in-a-fresh-process)

(alter-var-root #'session-steps/turn-ends-on-session
  (fn [original]
    (fn [name]
      (original name)
      (when-let [store (session-store/registered-store)]
        (session-store/clear-in-flight! store name)))))
(alter-var-root #'http-steps/response-body-has-key
  (fn [original]
    (fn [key]
      (if (= "application/edn" (get-in (g/get :http-response) [:headers "Content-Type"]))
        (g/should (contains? (edn/read-string (get-in (g/get :http-response) [:body])) (keyword key)))
        (original key)))))
(alter-var-root #'cli-steps/stdout-edn-contains
  (fn [original]
    (fn [table]
      (original (update table :rows
                        (fn [rows]
                          (mapv (fn [[path value :as row]]
                                  (if (= "input" path) [path (pr-str value)] row)) rows)))))))
(alter-var-root #'cli-steps/stdout-matches (constantly stdout-matches-and-captures))
(defthen "the turn Hail submitted has:" isaac.hail.handoff-steps/submitted-turn-has)
(defwhen "the turn queue ticks at {iso:string}" isaac.hail.handoff-steps/turn-queue-ticks-at)
