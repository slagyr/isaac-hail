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

(def ^:private short-uuid-re #"^[0-9a-f]{8}$")

(defn- short-uuid? [s]
  (and (string? s) (re-matches short-uuid-re s)))

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
                                                "{\"frequencies\":{\"band\":\"bean-pickup\"},\"params\":{\"n\":1}}"))
            body     (json/parse-string (:body response) true)
            id       (:id body)]
        (should= 201 (:status response))
        (should= "application/json" (get-in response [:headers "Content-Type"]))
        (should (short-uuid? id))
        (should= (str "/hail/" id) (get-in response [:headers "Location"]))
        (should= "http" (:from body))
        (should= id (:thread-id body))
        (should= {:id          id
                  :thread-id   id
                  :frequencies {:band "bean-pickup"}
                  :params      {:n 1}
                  :from        :http
                  :sent-at     "2026-05-24T17:00:00Z"}
                 (queue/read-pending id)))))

  (it "accepts EDN and returns 201 with the persisted hail as EDN"
    (binding [memory/*now* (java.time.Instant/parse "2026-05-24T17:00:00Z")]
      (let [response (sut/handler (post-request "application/edn"
                                                "{:frequencies {:band \"bean-pickup\"} :params {:n 1}}"))
            body     (edn/read-string (:body response))
            id       (:id body)]
        (should= 201 (:status response))
        (should= "application/edn" (get-in response [:headers "Content-Type"]))
        (should (short-uuid? id))
        (should= (str "/hail/" id) (get-in response [:headers "Location"]))
        (should= {:id          id
                  :thread-id   id
                  :frequencies {:band "bean-pickup"}
                  :params      {:n 1}
                  :from        :http
                  :sent-at     "2026-05-24T17:00:00Z"}
                 body))))

  (it "returns 400 with a structured error when frequencies is missing"
    (let [response (sut/handler (post-request "application/json"
                                              "{\"params\":{\"n\":1}}"))
          body     (json/parse-string (:body response) true)]
      (should= 400 (:status response))
      (should= "application/json" (get-in response [:headers "Content-Type"]))
      (should= "missing frequencies" (:error body))
      (should= "include :frequencies with :band, :session, :session-tags, or :crew" (:hint body))))

  (it "returns 400 with a structured error when direct addressing omits prompt"
    (let [response (sut/handler (post-request "application/json"
                                              "{\"frequencies\":{\"session-tags\":[\"wip\"]}}"))
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
