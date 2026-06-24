(ns isaac.hail.http-spec
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [isaac.config.api :as config]
    [isaac.fs :as fs]
    [isaac.hail.http :as sut]
    [isaac.hail.queue :as queue]
    [isaac.nexus :as nexus]
    [isaac.tool.memory :as memory]
    [speclj.core :refer :all]))

(defn- post-request [content-type body]
  {:request-method :post
   :uri            "/hail/send"
   :headers        {"content-type" content-type}
   :body           body})

(describe "hail HTTP handler"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (config/dangerously-install-config! {:root "/test/isaac"} "spec")
      (example)))

  (it "accepts JSON and returns 201 with the persisted hail as JSON"
    (binding [memory/*now* (java.time.Instant/parse "2026-05-24T17:00:00Z")]
      (let [response (sut/handler (post-request "application/json"
                                                "{\"frequency\":{\"band\":\"bean-pickup\"},\"payload\":{\"n\":1}}"))
            body     (json/parse-string (:body response) true)]
        (should= 201 (:status response))
        (should= "application/json" (get-in response [:headers "Content-Type"]))
        (should= "/hail/hail-1" (get-in response [:headers "Location"]))
        (should= "hail-1" (:id body))
        (should= "http" (:from body))
        (should= {:id        "hail-1"
                  :thread-id "hail-1"
                  :frequency {:band "bean-pickup"}
                  :payload   {:n 1}
                  :from      :http
                  :sent-at   "2026-05-24T17:00:00Z"}
                 (queue/read-pending "hail-1")))))

  (it "accepts EDN and returns 201 with the persisted hail as EDN"
    (binding [memory/*now* (java.time.Instant/parse "2026-05-24T17:00:00Z")]
      (let [response (sut/handler (post-request "application/edn"
                                                "{:frequency {:band \"bean-pickup\"} :payload {:n 1}}"))
            body     (edn/read-string (:body response))]
        (should= 201 (:status response))
        (should= "application/edn" (get-in response [:headers "Content-Type"]))
        (should= "/hail/hail-1" (get-in response [:headers "Location"]))
        (should= {:id        "hail-1"
                  :thread-id "hail-1"
                  :frequency {:band "bean-pickup"}
                  :payload   {:n 1}
                  :from      :http
                  :sent-at   "2026-05-24T17:00:00Z"}
                 body))))

  (it "returns 400 with a structured error when frequency is missing"
    (let [response (sut/handler (post-request "application/json"
                                              "{\"payload\":{\"n\":1}}"))
          body     (json/parse-string (:body response) true)]
      (should= 400 (:status response))
      (should= "application/json" (get-in response [:headers "Content-Type"]))
      (should= "missing frequency" (:error body))
      (should= "include :frequency with at least one field" (:hint body))))

  (it "returns 400 with a structured error when direct addressing omits prompt"
    (let [response (sut/handler (post-request "application/json"
                                              "{\"frequency\":{\"session-tags\":[\"wip\"]}}"))
          body     (json/parse-string (:body response) true)]
      (should= 400 (:status response))
      (should= "missing prompt" (:error body))
      (should= "include :prompt for non-band hails" (:hint body))))

  (it "returns 400 with a structured error for malformed JSON"
    (let [response (sut/handler (post-request "application/json" "{not valid json"))
          body     (json/parse-string (:body response) true)]
      (should= 400 (:status response))
      (should= "invalid body" (:error body))
      (should= "request body could not be parsed" (:hint body)))))
