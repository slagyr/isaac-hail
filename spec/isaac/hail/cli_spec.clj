(ns isaac.hail.cli-spec
  (:require
    [cheshire.core :as json]
    [isaac.fs :as fs]
    [isaac.hail.cli :as sut]
    [isaac.nexus :as nexus]
    [isaac.tool.memory :as memory]
    [speclj.core :refer :all]))

(describe "hail cli"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (example)))

  (it "prints the hail id by default"
    (let [output (with-out-str
                   (should= 0 (sut/run-fn {:_raw-args ["send" "--band" "bean-pickup" "--payload" "{:n 1}"]})))]
      (should= "hail-1\n" output)
      (should= "{:n 1}"
               (pr-str (:payload (sut/read-pending "hail-1"))))))

  (it "prints the full hail record as JSON"
    (binding [memory/*now* (java.time.Instant/parse "2026-05-23T12:00:00Z")]
      (let [output (with-out-str
                     (should= 0 (sut/run-fn {:_raw-args ["send" "--band" "bean-pickup" "--payload" "{:n 1}" "--json"]})))
            value  (json/parse-string output true)]
        (should= "hail-1" (:id value))
        (should= "bean-pickup" (get-in value [:frequency :band]))
        (should= 1 (get-in value [:payload :n]))
        (should= "cli" (:from value))
        (should= "2026-05-23T12:00:00Z" (:sent-at value)))))

  (it "reads a whole hail record from stdin"
    (let [output (with-in-str "{:frequency {:band \"bean-pickup\"} :payload {:n 1}}"
                   (with-out-str
                     (should= 0 (sut/run-fn {:_raw-args ["send" "-"]}))))]
      (should= "hail-1\n" output)
      (should= {:band "bean-pickup"}
               (:frequency (sut/read-pending "hail-1")))))

  (it "ignores stdin-supplied id and sent-at"
    (binding [memory/*now* (java.time.Instant/parse "2026-05-23T12:00:00Z")]
      (let [output (with-in-str "{:id \"stdin-id\" :sent-at \"2000-01-01T00:00:00Z\" :frequency {:band \"bean-pickup\"}}"
                     (with-out-str
                       (should= 0 (sut/run-fn {:_raw-args ["send" "-"]}))))]
        (should= "hail-1\n" output)
        (should= {:id        "hail-1"
                  :sent-at   "2026-05-23T12:00:00Z"
                  :frequency {:band "bean-pickup"}
                  :from      :cli}
                 (select-keys (sut/read-pending "hail-1") [:id :sent-at :frequency :from])))))

  (it "accepts --crew and persists it under :frequency :crew"
    (let [output (with-out-str
                   (should= 0 (sut/run-fn {:_raw-args ["send" "--crew" "marvin" "--prompt" "Heads up" "--payload" "{:n 1}"]})))]
      (should= "hail-1\n" output)
      (should= {:crew [:marvin]}
               (:frequency (sut/read-pending "hail-1")))
      (should= "Heads up"
               (:prompt (sut/read-pending "hail-1")))))

  (it "accepts --session and persists it under :frequency :session"
    (let [output (with-out-str
                   (should= 0 (sut/run-fn {:_raw-args ["send" "--session" "alpha" "--prompt" "Heads up" "--payload" "{:n 1}"]})))]
      (should= "hail-1\n" output)
      (should= {:session [:alpha]}
               (:frequency (sut/read-pending "hail-1")))
      (should= "Heads up"
               (:prompt (sut/read-pending "hail-1")))))

  (it "accepts repeatable tag flags and persists them as keyword sets"
    (let [output (with-out-str
                   (should= 0 (sut/run-fn {:_raw-args ["send" "--crew-tag" "role/worker" "--crew-tag" "wip" "--prompt" "go"]})))]
      (should= "hail-1\n" output)
      (should= {:crew-tags #{:role/worker :wip}}
               (:frequency (sut/read-pending "hail-1")))))

  (it "accepts repeatable --session-tag values and persists them as a keyword set"
    (let [output (with-out-str
                   (should= 0 (sut/run-fn {:_raw-args ["send" "--session-tag" "project/chess" "--session-tag" "wip" "--prompt" "go"]})))]
      (should= "hail-1\n" output)
      (should= {:session-tags #{:project/chess :wip}}
               (:frequency (sut/read-pending "hail-1")))))

  (it "combines distinct direct-addressing flags into one frequency map"
    (let [output (with-out-str
                   (should= 0 (sut/run-fn {:_raw-args ["send" "--crew" "marvin" "--session-tag" "project/chess" "--prompt" "go"]})))]
      (should= "hail-1\n" output)
      (should= {:crew [:marvin] :session-tags #{:project/chess}}
               (:frequency (sut/read-pending "hail-1")))))

  (it "accepts --reach for direct/tag addressing"
    (let [output (with-out-str
                   (should= 0 (sut/run-fn {:_raw-args ["send" "--crew" "marvin" "--reach" "all" "--prompt" "go"]})))]
      (should= "hail-1\n" output)
      (should= {:crew [:marvin] :reach :all}
               (:frequency (sut/read-pending "hail-1")))))

  (it "reads a whole hail record from stdin as JSON when --from-json is given"
    (let [output (with-in-str "{\"frequency\":{\"band\":\"bean-pickup\"},\"payload\":{\"n\":1}}"
                   (with-out-str
                     (should= 0 (sut/run-fn {:_raw-args ["send" "-" "--from-json"]}))))]
      (should= "hail-1\n" output)
      (should= {:band "bean-pickup"}
               (:frequency (sut/read-pending "hail-1")))
      (should= {:n 1}
               (:payload (sut/read-pending "hail-1")))))

  (it "rejects direct addressing without a prompt"
    (let [err* (java.io.StringWriter.)]
      (binding [*err* err*]
        (should= 1 (sut/run-fn {:_raw-args ["send" "--crew" "marvin" "--payload" "{:n 1}"]})))
      (let [err (str err*)]
        (should (.contains err "--prompt")))))

  (it "rejects --reach without direct or tag addressing"
    (let [err* (java.io.StringWriter.)]
      (binding [*err* err*]
        (should= 1 (sut/run-fn {:_raw-args ["send" "--band" "bean-pickup" "--reach" "all"]})))
      (let [err (str err*)]
        (should (.contains err "--reach"))))))
