(ns isaac.hail.cli
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.tools.cli :as tools-cli]
    [isaac.cli.api :as cli-api]
    [isaac.cli.common :as cli-common]
    [isaac.cli.host :as host]
    [isaac.config.loader :as loader]
    [isaac.hail.band-resolve :as band-resolve]
    [isaac.hail.delivery-worker :as delivery-worker]
    [isaac.hail.queue :as queue]
    [isaac.hail.store :as store]
    [isaac.nexus :as nexus]))

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
   [nil "--reach MODE" "Reach mode (:one or :all) for direct/tag addressing"]
   [nil "--prompt TEXT" "Prompt override (band hails may omit when the band has a template)"]
   [nil "--params EDN" "Band template parameters (EDN map)"]
   [nil "--reply-to ID" "Hail id this message replies to"]
   [nil "--thread-id ID" "Thread id (defaults to hail id or inherited from reply-to)"]
   [nil "--from-json" "Read whole-hail stdin input as JSON"]
   [nil "--json" "Print the full hail record as JSON"]
   [nil "--edn" "Print the full hail record as EDN"]
    [nil "--dry-run" "Validate and print the record without enqueueing"]])

(defn hail-help []
  (str "Usage: isaac hail <subcommand> [options]\n\n"
       "Send and inspect hail records.\n\n"
       "Subcommands:\n"
       "  send    Persist a hail record to hail/pending (--dry-run to validate only)\n"
       "  show    Print a hail record by id\n"
       "  drop    Move a bound-unclaimed delivery to hail/undeliverable\n"))

(defn- send-help []
  (str "Usage: isaac hail send [addressing flags] [--prompt <text>] [--params <edn>] [--json|--edn] [--dry-run]\n"
       "       isaac hail send - [--from-json]\n\n"
       "Persist a hail record to hail/pending (--dry-run to validate only).\n\n"
       "Options:\n"
       "  -h, --help                 Show help\n"
       "      --band NAME            Band name\n"
       "      --crew ID              Crew session frequencies\n"
       "      --session ID           Session id (repeatable)\n"
       "      --session-tag TAG      Session tag (repeatable)\n"
       "      --reach MODE           Reach mode (:one or :all) for direct/tag addressing\n"
       "      --prompt TEXT          Prompt for direct/tag-addressed hails\n"
       "      --params EDN           Band template parameters (EDN map)\n"
       "      --reply-to ID          Hail id this message replies to\n"
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
  [[:crew "--crew"] [:session "--session"] [:session-tag "--session-tag"] [:reach "--reach"]])

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
    (:session-tag options) (assoc :session-tags (keyword-set* (:session-tag options)))
    (:reach options)       (assoc :reach (flag-keyword (:reach options)))))

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

      (and (not whole-hail?) (:reach options) (not direct?))
      (conj "Option --reach requires direct/tag addressing")

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
      (conj "Direct/tag-addressed hails require :prompt")

      (and (:reach frequencies) (not direct?))
      (conj "Hails with :reach require direct/tag addressing"))))

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
      (:thread-id options) (assoc :thread-id (:thread-id options)))))

(defn- print-record! [record options]
  (cond
    (:json options) (cli-common/print-json! record)
    (:edn options)  (cli-common/print-edn! record)
    (:dry-run options) (cli-common/print-edn! record)
    :else           (println (:id record))))

(defn- run-send [args]
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
            (let [record (if (:dry-run options)
                           (queue/check-readable! record)
                           (queue/send! record))]
              (print-record! record options)
              0)
            (catch clojure.lang.ExceptionInfo e
              (if (= :hail/unreadable-record (:type (ex-data e)))
                (do (binding [*out* *err*] (println (ex-message e))) 1)
                (throw e)))))))))

(defn run [args]
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
      (run-send (rest arguments))

      (= "show" (first arguments))
      (let [id (second arguments)]
        (if (str/blank? id)
          (do (binding [*out* *err*] (println "Usage: isaac hail show <id>")) 1)
          (if-let [record (store/find-by-id id)]
            (do (cli-common/print-edn! record) 0)
            (do (binding [*out* *err*] (println (str "hail not found: " id))) 1))))

      (= "drop" (first arguments))
      (let [id (second arguments)]
        (if (str/blank? id)
          (do (binding [*out* *err*] (println "Usage: isaac hail drop <id>")) 1)
          (try
            (delivery-worker/drop! (or (nexus/get :root) (loader/root)) id)
            (println id)
            0
            (catch Exception e
              (binding [*out* *err*]
                (println (or (.getMessage e) id)))
              1))))

      (= "requeue" (first arguments))
      (let [id (second arguments)]
        (if (str/blank? id)
          (do (binding [*out* *err*] (println "Usage: isaac hail requeue <id>")) 1)
          (try
            (delivery-worker/requeue! (loader/root) id)
            0
            (catch Exception e
              (binding [*out* *err*]
                (println (or (.getMessage e) id)))
              1))))

      :else
      (do
        (binding [*out* *err*]
          (println (str "Unknown hail subcommand: " (or (first arguments) ""))))
        1))))

(defn run-fn [{:keys [_raw-args]}]
  (run (or _raw-args [])))

(defn read-pending [id]
  (queue/read-pending id))

;; ----- :isaac/cli berth implementation -----

(defmethod cli-api/run :hail [_id opts]
  (run-fn opts))

(defmethod cli-api/option-spec :hail [_id]
  hail-option-spec)

(defmethod cli-api/help :hail [_id]
  (hail-help))

(defmethod cli-api/subcommands :hail [_id]
  [{:name "send" :summary "Persist a hail record to hail/pending"}
   {:name "show" :summary "Print a hail record by id"}
   {:name "drop" :summary "Move a bound-unclaimed delivery to hail/undeliverable"}])