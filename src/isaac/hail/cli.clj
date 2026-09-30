(ns isaac.hail.cli
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.tools.cli :as tools-cli]
    [isaac.agent.config.runtime :as runtime]
    [isaac.foundation.cli.api :as cli-api]
    [isaac.foundation.cli.common :as cli-common]
    [isaac.foundation.cli.host :as host]
    [isaac.foundation.config.loader :as loader]
    [isaac.foundation.config.root :as root]
    [isaac.foundation.fs :as fs]
    [isaac.hail.band-resolve :as band-resolve]
    [isaac.hail.queue :as queue]))

(def hail-option-spec
  [["-h" "--help" "Show help"]])

(def ^:private send-option-spec
  [["-h" "--help" "Show help"]
   [nil "--band NAME" "Band name"]
   [nil "--crew ID" "Crew session frequencies (sessions whose :crew matches)"]
   [nil "--session ID" "Session id (repeatable)"
    :assoc-fn (fn [m k v] (update m k (fnil conj []) v))]
   [nil "--session-tag TAG" "Session tag (repeatable)"
    :assoc-fn (fn [m k v] (update m k (fnil conj []) v))]
   [nil "--prompt TEXT" "Prompt override (band hails may omit when the band has a template)"]
   [nil "--params EDN" "Band template parameters (EDN map)"]
   [nil "--reply-to ID" "Turn id this message replies to"]
   [nil "--idempotency-key KEY" "Reuse an accepted turn for a retried send"]
   [nil "--thread-id ID" "Thread id (defaults to hail id or inherited from reply-to)"]
   [nil "--from-json" "Read whole-hail stdin input as JSON"]
   [nil "--json" "Print the full hail record as JSON"]
   [nil "--edn" "Print the full hail record as EDN"]
    [nil "--dry-run" "Validate and print the record without enqueueing"]])

(defn hail-help []
  (str "Usage: isaac hail <subcommand> [options]\n\n"
       "Submit hails to Agent turn queue.\n\n"
       "Subcommands:\n"
       "  send    Submit a turn to Agent (--dry-run to validate only)\n"))

(defn- send-help []
  (str "Usage: isaac hail send [addressing flags] [--prompt <text>] [--params <edn>] [--json|--edn] [--dry-run]\n"
       "       isaac hail send - [--from-json]\n\n"
       "Submit a turn to Agent (--dry-run to validate only).\n\n"
       "Options:\n"
       "  -h, --help                 Show help\n"
       "      --band NAME            Band name\n"
       "      --crew ID              Crew session frequencies\n"
       "      --session ID           Session id (repeatable)\n"
       "      --session-tag TAG      Session tag (repeatable)\n"
       "      --prompt TEXT          Prompt for direct/tag-addressed hails\n"
       "      --params EDN           Band template parameters (EDN map)\n"
       "      --reply-to ID          Turn id this message replies to\n"
       "      --idempotency-key KEY Reuse an accepted turn for a retried send\n"
       "      --thread-id ID         Thread id (defaults to hail id or inherited from reply-to)\n"
       "      --from-json            Read whole-hail stdin input as JSON\n"
       "      --json                 Print the full hail record as JSON\n"
       "      --edn                  Print the full hail record as EDN\n"
       "      --dry-run              Validate and print the record without enqueueing\n"))

(defn- slurp-stdin []
  (let [content (slurp (host/in))]
    (when-not (str/blank? content)
      content)))

(defn- read-edn [text]
  (edn/read-string text))

(defn- read-json [text]
  (json/parse-string text true))

(defn- flag-name
  "A flag value typed with its leading colon (:project/foo) names the same
   keyword as the bare form (project/foo)."
  [value]
  (cond-> value (str/starts-with? value ":") (subs 1)))

(defn- flag-keyword [value]
  (keyword (flag-name value)))

(defn- readable-keyword? [kw]
  (try
    (= kw (edn/read-string (pr-str kw)))
    (catch Exception _ false)))

(def ^:private keyword-flags
  [[:crew "--crew"] [:session "--session"] [:session-tag "--session-tag"]])

(defn- flag-values [value]
  (cond (sequential? value) value
        (some? value)       [value]))

(defn- keyword-flag-errors [options]
  (for [[option flag] keyword-flags
        value         (flag-values (get options option))
        :when         (not (readable-keyword? (flag-keyword value)))]
    (str "Invalid " flag " value " (pr-str value) ": not a readable keyword")))

(defn- keywordize* [values]
  (mapv flag-keyword values))

(defn- keyword-set* [values]
  (into #{} (map flag-keyword) values))

(defn- direct-addressing? [frequencies]
  (boolean (some #(contains? frequencies %)
                 [:session :session-tags :crew])))

(defn- has-addressing? [frequencies]
  (boolean (some #(contains? frequencies %)
                 [:band :session :session-tags :crew])))

(defn- frequencies-from-options [options]
  (cond-> {}
    (:band options)        (assoc :band (:band options))
    (:crew options)        (assoc :crew (flag-name (:crew options)))
    (:session options)     (assoc :session (keywordize* (:session options)))
    (:session-tag options) (assoc :session-tags (keyword-set* (:session-tag options)))))

(defn- parse-whole-hail [options]
  (let [text (or (slurp-stdin) "{}")]
    (if (:from-json options)
      (read-json text)
      (read-edn text))))

(defn- build-errors [whole-hail? options]
  (let [frequencies       (when-not whole-hail? (frequencies-from-options options))
        direct?           (direct-addressing? frequencies)
        band?             (contains? frequencies :band)
        has-addressing?   (has-addressing? frequencies)
        template-band?    (and (:band options) (band-resolve/template-band? (:band options)))]
    (cond-> (vec (keyword-flag-errors options))
      template-band?
      (conj (str "Band " (:band options) " is a template and cannot be hailed"))

      (and (not whole-hail?) (not has-addressing?))
      (conj "At least one addressing option is required")

      (and (not whole-hail?) direct? (not band?) (str/blank? (:prompt options)))
      (conj "Missing required option --prompt for direct/tag addressing")


      (and (:json options) (:edn options))
      (conj "Choose either --json or --edn, not both"))))

(defn- validate-hail [record]
  (let [frequencies (or (:frequencies record) {})
        direct?     (direct-addressing? frequencies)
        band?       (contains? frequencies :band)
        band-name   (:band frequencies)]
    (cond-> []
      (and band? (band-resolve/template-band? band-name))
      (conj (str "Band " band-name " is a template and cannot be hailed"))

      (contains? record :crew)
      (conj "Top-level :crew is not supported; use :frequencies {:crew ...}")

      (not (has-addressing? frequencies))
      (conj "Hail must include at least one addressing field (:band, :session, :session-tags, or :crew)")

      (and direct? (not band?) (str/blank? (:prompt record)))
      (conj "Direct/tag-addressed hails require :prompt"))))

(defn- parse-send-opts [args]
  (let [whole-hail?                        (= "-" (first args))
        parse-args                         (if whole-hail? (rest args) args)
        {:keys [arguments errors options]} (tools-cli/parse-opts parse-args send-option-spec :in-order true)
        errors                             (into (vec errors) (build-errors whole-hail? options))]
    {:arguments (if whole-hail? ["-"] arguments)
     :errors    errors
     :options   (->> options
                     (remove (comp nil? val))
                     (into {}))}))

(defn- whole-hail-stdin? [arguments]
  (= ["-"] arguments))

(defn- build-hail [{:keys [arguments options]}]
  (if (whole-hail-stdin? arguments)
    (assoc (parse-whole-hail options) :from :cli)
    (cond-> {:frequencies (frequencies-from-options options)
             :from        :cli}
      (:prompt options)   (assoc :prompt (:prompt options))
      (:params options)   (assoc :params (read-edn (:params options)))
      (:reply-to options) (assoc :reply-to (:reply-to options))
      (:thread-id options) (assoc :thread-id (:thread-id options))
      (:idempotency-key options) (assoc :idempotency-key (:idempotency-key options)))))

(defn- print-record! [record options]
  (cond
    (:json options) (cli-common/print-json! (merge record (select-keys (:origin record) [:from :params])))
    (:edn options)  (cli-common/print-edn! (merge record (select-keys (:origin record) [:from :params])))
    (:dry-run options) (cli-common/print-edn! record)
    :else           (println (:id record))))

(defn- ensure-runtime!
  "Boots the Agent runtime (session store) for this process before send
   resolves/submits a turn. A real shell starts with nothing registered —
   only the server and its in-process callers (HTTP route, hail-send tool)
   already have a live runtime. Mirrors isaac.agent.session.cli/install-cli!."
  [opts]
  (host/ensure-runtime!
    {:install!
     (fn []
       (runtime/install!
         {:config (loader/load-config! (root/default-root opts) (fs/instance) "hail send")}))}))

(defn- run-send [opts args]
  (let [{:keys [errors options] :as parsed} (parse-send-opts args)]
    (cond
      (:help options)
      (do (println (send-help)) 0)

      (seq errors)
      (do
        (doseq [error errors]
          (binding [*out* *err*]
            (println error)))
        1)

      :else
      (let [record (build-hail parsed)
            errors (validate-hail record)]
        (if (seq errors)
          (do
            (doseq [error errors]
              (binding [*out* *err*]
                (println error)))
            1)
          (try
            (ensure-runtime! opts)
            (let [record (if (:dry-run options)
                           (queue/check-readable!
                             {:frequencies (:frequencies record)
                              :input (:prompt record)
                              :origin (select-keys (assoc record :from :cli) [:from :params])})
                           (queue/send! record))]
              (print-record! record options)
              0)
            (catch clojure.lang.ExceptionInfo e
              (do (binding [*out* *err*] (println (ex-message e))) 1))))))))

(defn run
  ([args] (run {} args))
  ([opts args]
   (let [{:keys [arguments errors options]} (tools-cli/parse-opts args hail-option-spec :in-order true)]
     (cond
       (:help options)
       (do (println (hail-help)) 0)

       (seq errors)
       (do
         (doseq [error errors]
           (binding [*out* *err*]
             (println error)))
         1)

       (= "send" (first arguments))
       (run-send opts (rest arguments))

       :else
       (do
         (binding [*out* *err*]
           (println (str "Unknown hail subcommand: " (or (first arguments) ""))))
         1)))))

(defn run-fn [{:keys [_raw-args] :as opts}]
  (run opts (or _raw-args [])))


;; ----- :isaac/cli berth implementation -----

(defmethod cli-api/run :hail [_id opts]
  (run-fn opts))

(defmethod cli-api/option-spec :hail [_id]
  hail-option-spec)

(defmethod cli-api/help :hail [_id]
  (hail-help))

(defmethod cli-api/subcommands :hail [_id]
  [{:name "send" :summary "Submit a turn to Agent"}])