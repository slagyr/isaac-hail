(ns isaac.hail.cli-spec
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.cli.host :as host]
    [isaac.cli.registry :as registry]
    [isaac.config.api :as config-api]
    [isaac.fs :as fs]
    [isaac.hail.cli :as sut]
    [isaac.nexus :as nexus]
    [isaac.tool.memory :as memory]
    [speclj.core :refer :all]))

(def ^:private short-uuid-re #"^[0-9a-f]{8}$")

(defn- short-uuid? [s]
  (and (string? s) (re-matches short-uuid-re s)))

(defn- hail-id-from-output [output]
  (str/trim output))

(describe "hail cli"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (example)))

  (it "prints the hail id by default"
    (let [output (with-out-str
                   (should= 0 (sut/run-fn {:_raw-args ["send" "--band" "bean-pickup" "--params" "{:n 1}"]})))]
      (let [id (hail-id-from-output output)]
        (should (short-uuid? id))
        (should= {:n 1} (:params (sut/read-pending id))))))

  (it "prints the full hail record as JSON"
    (binding [memory/*now* (java.time.Instant/parse "2026-05-23T12:00:00Z")]
      (let [output (with-out-str
                     (should= 0 (sut/run-fn {:_raw-args ["send" "--band" "bean-pickup" "--params" "{:n 1}" "--json"]})))
            value  (json/parse-string output true)]
        (should (short-uuid? (:id value)))
        (should= "bean-pickup" (get-in value [:frequencies :band]))
        (should= 1 (get-in value [:params :n]))
        (should= "cli" (:from value))
        (should= "2026-05-23T12:00:00Z" (:sent-at value)))))

  (it "reads a whole hail record from stdin"
    (let [output (with-in-str "{:frequencies {:band \"bean-pickup\"} :params {:n 1}}"
                   (with-out-str
                     (should= 0 (sut/run-fn {:_raw-args ["send" "-"]}))))]
      (let [id (hail-id-from-output output)]
        (should (short-uuid? id))
        (should= {:band "bean-pickup"} (:frequencies (sut/read-pending id)))
        (should= {:n 1} (:params (sut/read-pending id))))))

  (it "ignores stdin-supplied id and sent-at"
    (binding [memory/*now* (java.time.Instant/parse "2026-05-23T12:00:00Z")]
      (let [output (with-in-str "{:id \"stdin-id\" :sent-at \"2000-01-01T00:00:00Z\" :frequencies {:band \"bean-pickup\"}}"
                     (with-out-str
                       (should= 0 (sut/run-fn {:_raw-args ["send" "-"]}))))]
        (let [id (hail-id-from-output output)]
          (should (short-uuid? id))
          (should= {:id          id
                    :sent-at     "2026-05-23T12:00:00Z"
                    :frequencies {:band "bean-pickup"}
                    :from        :cli}
                   (select-keys (sut/read-pending id) [:id :sent-at :frequencies :from]))))))

  (it "accepts --crew and persists it under :frequencies :crew"
    (let [output (with-out-str
                   (should= 0 (sut/run-fn {:_raw-args ["send" "--crew" "marvin" "--session-tag" "wip" "--prompt" "Heads up"]})))]
      (let [id (hail-id-from-output output)]
        (should (short-uuid? id))
        (should= {:crew "marvin" :session-tags #{:wip}} (:frequencies (sut/read-pending id)))
        (should= "Heads up" (:prompt (sut/read-pending id))))))

  (it "accepts --session and persists it under :frequencies :session"
    (let [output (with-out-str
                   (should= 0 (sut/run-fn {:_raw-args ["send" "--session" "alpha" "--prompt" "Heads up"]})))]
      (let [id (hail-id-from-output output)]
        (should (short-uuid? id))
        (should= {:session [:alpha]} (:frequencies (sut/read-pending id)))
        (should= "Heads up" (:prompt (sut/read-pending id))))))

  (it "accepts repeatable --session-tag values and persists them as a keyword set"
    (let [output (with-out-str
                   (should= 0 (sut/run-fn {:_raw-args ["send" "--session-tag" "project/chess" "--session-tag" "wip" "--prompt" "go"]})))]
      (let [id (hail-id-from-output output)]
        (should (short-uuid? id))
        (should= {:session-tags #{:project/chess :wip}} (:frequencies (sut/read-pending id))))))

  (it "combines --crew with --session-tag into frequency selectors"
    (let [output (with-out-str
                   (should= 0 (sut/run-fn {:_raw-args ["send" "--crew" "marvin" "--session-tag" "project/chess" "--prompt" "go"]})))]
      (let [id (hail-id-from-output output)]
        (should (short-uuid? id))
        (should= {:crew "marvin" :session-tags #{:project/chess}}
                 (:frequencies (sut/read-pending id))))))

  (it "accepts --reach for direct/tag addressing"
    (let [output (with-out-str
                   (should= 0 (sut/run-fn {:_raw-args ["send" "--session-tag" "wip" "--reach" "all" "--prompt" "go"]})))]
      (let [id (hail-id-from-output output)]
        (should (short-uuid? id))
        (should= {:session-tags #{:wip} :reach :all}
                 (:frequencies (sut/read-pending id))))))

  (it "reads a whole hail record from stdin as JSON when --from-json is given"
    (let [output (with-in-str "{\"frequencies\":{\"band\":\"bean-pickup\"},\"params\":{\"n\":1}}"
                   (with-out-str
                     (should= 0 (sut/run-fn {:_raw-args ["send" "-" "--from-json"]}))))]
      (let [id (hail-id-from-output output)]
        (should (short-uuid? id))
        (should= {:band "bean-pickup"} (:frequencies (sut/read-pending id)))
        (should= {:n 1} (:params (sut/read-pending id))))))

  (it "rejects direct addressing without a prompt"
    (let [err* (java.io.StringWriter.)]
      (binding [*err* err*]
        (should= 1 (sut/run-fn {:_raw-args ["send" "--session-tag" "wip"]})))
      (let [err (str err*)]
        (should (.contains err "--prompt")))))

  (it "rejects --reach without direct or tag addressing"
    (let [err* (java.io.StringWriter.)]
      (binding [*err* err*]
        (should= 1 (sut/run-fn {:_raw-args ["send" "--band" "bean-pickup" "--reach" "all"]})))
      (let [err (str err*)]
        (should (.contains err "--reach")))))

  (it "requeue moves failed delivery back to deliveries"
    (fs/mkdirs (nexus/get :fs) "/test/isaac/hail/failed")
    (fs/spit (nexus/get :fs) "/test/isaac/hail/failed/hail-9.edn"
             "{:id \"hail-9\" :prompt \"Seal.\" :attempts 5 :error :api-error}")
    (should= 0 (sut/run-fn {:_raw-args ["requeue" "hail-9"]}))
    (should-not (fs/exists? (nexus/get :fs) "/test/isaac/hail/failed/hail-9.edn"))
    (should= 0 (get-in (read-string (fs/slurp (nexus/get :fs) "/test/isaac/hail/deliveries/hail-9.edn")) [:attempts])))

  (it "drop moves a bound delivery to undeliverable"
    (fs/mkdirs (nexus/get :fs) "/test/isaac/hail/deliveries")
    (fs/spit (nexus/get :fs) "/test/isaac/hail/deliveries/hail-9.edn"
             "{:id \"hail-9\" :bound-session :engine-room :attempts 0}")
    (let [output (with-out-str (should= 0 (sut/run-fn {:_raw-args ["drop" "hail-9"]})))]
      (should-contain "hail-9" output))
    (should-not (fs/exists? (nexus/get :fs) "/test/isaac/hail/deliveries/hail-9.edn"))
    (should= :dropped
             (:reason (read-string (fs/slurp (nexus/get :fs)
                                             "/test/isaac/hail/undeliverable/hail-9.edn")))))

  (it "drop unknown id exits 1 and mentions id"
    (let [err* (java.io.StringWriter.)]
      (binding [*err* err*]
        (should= 1 (sut/run-fn {:_raw-args ["drop" "nope99"]})))
      (should (.contains (str err*) "nope99"))))

  (it "requeue unknown id exits 1 and mentions id"
    (let [err* (java.io.StringWriter.)]
      (binding [*err* err*]
        (should= 1 (sut/run-fn {:_raw-args ["requeue" "nope99"]})))
      (should (.contains (str err*) "nope99"))))

  (it "dry-run validates a band send, prints the record, and writes nothing to pending"
    (let [output (with-out-str
                   (should= 0 (sut/run-fn {:_raw-args ["send" "--dry-run" "--band" "b" "--params" "{:bean-id \"x\"}"]})))
          record (edn/read-string output)
          pending-dir "/test/isaac/hail/pending"]
      (should= {:bean-id "x"} (:params record))
      (should= {:band "b"} (:frequencies record))
      (should-be-nil (:id record))
      (should-be-nil (:sent-at record))
      (should-not (fs/exists? (nexus/get :fs) pending-dir))))

  (it "dry-run --json prints JSON of the same unenqueued record"
    (let [output (with-out-str
                   (should= 0 (sut/run-fn {:_raw-args ["send" "--dry-run" "--band" "b" "--params" "{:bean-id \"x\"}" "--json"]})))
          value  (json/parse-string output true)]
      (should= {:bean-id "x"} (:params value))
      (should= "b" (get-in value [:frequencies :band]))
      (should-be-nil (:id value))
      (should-be-nil (:sent-at value))
      (should-not (fs/exists? (nexus/get :fs) "/test/isaac/hail/pending"))))

  (it "dry-run with a validation error exits 1, prints stderr, and enqueues nothing"
    (let [err* (java.io.StringWriter.)]
      (binding [*err* err*]
        (should= 1 (sut/run-fn {:_raw-args ["send" "--dry-run" "--session-tag" "wip"]})))
      (should (.contains (str err*) "--prompt"))
      (should-not (fs/exists? (nexus/get :fs) "/test/isaac/hail/pending"))))

  (it "dry-run of whole-hail stdin --from-json echoes the record and enqueues nothing"
    (let [output (with-in-str "{\"frequencies\":{\"band\":\"bean-pickup\"},\"params\":{\"n\":1}}"
                   (with-out-str
                     (should= 0 (sut/run-fn {:_raw-args ["send" "-" "--from-json" "--dry-run"]}))))
          record (edn/read-string output)]
      (should= {:band "bean-pickup"} (:frequencies record))
      (should= {:n 1} (:params record))
      (should-be-nil (:id record))
      (should-not (fs/exists? (nexus/get :fs) "/test/isaac/hail/pending"))))

  (it "send --help lists --reply-to, --thread-id, and --dry-run"
    (let [output (with-out-str
                   (should= 0 (sut/run-fn {:_raw-args ["send" "--help"]})))]
      (should (.contains output "--reply-to"))
      (should (.contains output "--thread-id"))
      (should (.contains output "--dry-run"))
      (should (.contains output "[--dry-run]"))))

  (it "hail --help send line mentions --dry-run to validate only"
    (let [output (with-out-str
                   (should= 0 (sut/run-fn {:_raw-args ["--help"]})))]
      (should (.contains output "--dry-run"))
      (should (.contains output "validate only"))))

  (it "show prints principal from a persisted hail"
    (fs/mkdirs (nexus/get :fs) "/test/isaac/hail/pending")
    (fs/spit (nexus/get :fs) "/test/isaac/hail/pending/abcd1234.edn"
             "{:id \"abcd1234\" :principal \"ci\" :from :http}")
    (let [output (with-out-str
                   (should= 0 (sut/run-fn {:_raw-args ["show" "abcd1234"]})))]
      (should (.contains output "principal"))
      (should (.contains output "ci"))))

  (it "declares the hail command hosted"
    (let [manifest (edn/read-string (slurp "src/isaac-manifest.edn"))]
      (should= true (get-in manifest [:isaac/cli :hail :hosted]))))

  (it "reads hail send - from the embedded host stream without mutating ambient runtime"
    (registry/register! {:name "hail" :hosted true :run-fn sut/run-fn})
    (let [before-nexus (nexus/necho)
          before-memo  (config-api/process-memo-snapshot)
          payload      "{\"frequencies\":{\"band\":\"bean-pickup\"},\"params\":{\"n\":1}}"
          out          (java.io.StringWriter.)
          err          (java.io.StringWriter.)
          exit         (host/run-embedded {:argv ["hail" "send" "-" "--from-json" "--dry-run"]
                                           :in   (java.io.StringReader. payload)
                                           :out  out
                                           :err  err
                                           :env  {}
                                           :cwd  "/test/isaac"
                                           :root "/test/isaac"})
          record       (edn/read-string (str out))]
      (should= 0 exit)
      (should= {:band "bean-pickup"} (:frequencies record))
      (should= {:n 1} (:params record))
      (should= before-nexus (nexus/necho))
      (should= before-memo (config-api/process-memo-snapshot))))

  (it "strips a leading colon from --session-tag values (isaac-k0xm)"
    (doseq [tag [":project/foo" "project/foo"]]
      (let [output (with-out-str
                     (should= 0 (sut/run-fn {:_raw-args ["send" "--band" "x" "--session-tag" tag "--dry-run"]})))]
        (should= #{:project/foo} (get-in (edn/read-string output) [:frequencies :session-tags])))))

  (it "refuses a --session-tag that does not read back as a keyword (isaac-k0xm)"
    (let [err* (java.io.StringWriter.)]
      (binding [*err* err*]
        (should= 1 (sut/run-fn {:_raw-args ["send" "--band" "x" "--session-tag" ":::x" "--dry-run"]})))
      (should (.contains (str err*) "--session-tag"))
      (should-not (fs/exists? (nexus/get :fs) "/test/isaac/hail/pending"))))

  (it "strips a leading colon from --crew and --session values (isaac-k0xm)"
    (let [output (with-out-str
                   (should= 0 (sut/run-fn {:_raw-args ["send" "--crew" ":yopp" "--session" ":abc" "--prompt" "go" "--dry-run"]})))
          freqs  (:frequencies (edn/read-string output))]
      (should= "yopp" (:crew freqs))
      (should= [:abc] (:session freqs))))

  (it "refuses unreadable --crew and --session values, naming the flag (isaac-k0xm)"
    (doseq [[flag value] [["--crew" "::yopp"] ["--session" "::abc"]]]
      (let [err* (java.io.StringWriter.)]
        (binding [*err* err*]
          (should= 1 (sut/run-fn {:_raw-args ["send" flag value "--prompt" "go" "--dry-run"]})))
        (should (.contains (str err*) flag)))))

  (it "dry-run refuses a record that does not read back as EDN (isaac-k0xm)"
    (let [err* (java.io.StringWriter.)]
      (binding [*err* err*]
        (should= 1 (with-in-str "{\"frequencies\":{\"band\":\"b\"},\"params\":{\":a/b\":1}}"
                     (sut/run-fn {:_raw-args ["send" "-" "--from-json" "--dry-run"]}))))
      (should (.contains (str err*) "unreadable"))))

  )
