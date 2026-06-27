(ns isaac.hail.cli-spec
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
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
                   (should= 0 (sut/run-fn {:_raw-args ["send" "--band" "bean-pickup" "--payload" "{:n 1}"]})))]
      (let [id (hail-id-from-output output)]
        (should (short-uuid? id))
        (should= "{:n 1}" (pr-str (:payload (sut/read-pending id)))))))

  (it "prints the full hail record as JSON"
    (binding [memory/*now* (java.time.Instant/parse "2026-05-23T12:00:00Z")]
      (let [output (with-out-str
                     (should= 0 (sut/run-fn {:_raw-args ["send" "--band" "bean-pickup" "--payload" "{:n 1}" "--json"]})))
            value  (json/parse-string output true)]
        (should (short-uuid? (:id value)))
        (should= "bean-pickup" (get-in value [:frequencies :band]))
        (should= 1 (get-in value [:payload :n]))
        (should= "cli" (:from value))
        (should= "2026-05-23T12:00:00Z" (:sent-at value)))))

  (it "reads a whole hail record from stdin"
    (let [output (with-in-str "{:frequencies {:band \"bean-pickup\"} :payload {:n 1}}"
                   (with-out-str
                     (should= 0 (sut/run-fn {:_raw-args ["send" "-"]}))))]
      (let [id (hail-id-from-output output)]
        (should (short-uuid? id))
        (should= {:band "bean-pickup"} (:frequencies (sut/read-pending id))))))

  (it "ignores stdin-supplied id and sent-at"
    (binding [memory/*now* (java.time.Instant/parse "2026-05-23T12:00:00Z")]
      (let [output (with-in-str "{:id \"stdin-id\" :sent-at \"2000-01-01T00:00:00Z\" :frequencies {:band \"bean-pickup\"}}"
                     (with-out-str
                       (should= 0 (sut/run-fn {:_raw-args ["send" "-"]}))))]
        (let [id (hail-id-from-output output)]
          (should (short-uuid? id))
          (should= {:id        id
                    :sent-at   "2026-05-23T12:00:00Z"
                    :frequencies {:band "bean-pickup"}
                    :from      :cli}
                   (select-keys (sut/read-pending id) [:id :sent-at :frequencies :from]))))))

  (it "accepts --crew and persists it under :frequencies :crew"
    (let [output (with-out-str
                   (should= 0 (sut/run-fn {:_raw-args ["send" "--crew" "marvin" "--session-tag" "wip" "--prompt" "Heads up" "--payload" "{:n 1}"]})))]
      (let [id (hail-id-from-output output)]
        (should (short-uuid? id))
        (should= {:crew "marvin" :session-tags #{:wip}} (:frequencies (sut/read-pending id)))
        (should= "Heads up" (:prompt (sut/read-pending id))))))

  (it "accepts --session and persists it under :frequencies :session"
    (let [output (with-out-str
                   (should= 0 (sut/run-fn {:_raw-args ["send" "--session" "alpha" "--prompt" "Heads up" "--payload" "{:n 1}"]})))]
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
    (let [output (with-in-str "{\"frequencies\":{\"band\":\"bean-pickup\"},\"payload\":{\"n\":1}}"
                   (with-out-str
                     (should= 0 (sut/run-fn {:_raw-args ["send" "-" "--from-json"]}))))]
      (let [id (hail-id-from-output output)]
        (should (short-uuid? id))
        (should= {:band "bean-pickup"} (:frequencies (sut/read-pending id)))
        (should= {:n 1} (:payload (sut/read-pending id))))))

  (it "rejects direct addressing without a prompt"
    (let [err* (java.io.StringWriter.)]
      (binding [*err* err*]
        (should= 1 (sut/run-fn {:_raw-args ["send" "--session-tag" "wip" "--payload" "{:n 1}"]})))
      (let [err (str err*)]
        (should (.contains err "--prompt")))))

  (it "rejects --reach without direct or tag addressing"
    (let [err* (java.io.StringWriter.)]
      (binding [*err* err*]
        (should= 1 (sut/run-fn {:_raw-args ["send" "--band" "bean-pickup" "--reach" "all"]})))
      (let [err (str err*)]
        (should (.contains err "--reach"))))))