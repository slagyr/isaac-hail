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

(defn- post-request
  ([content-type body]
   (post-request content-type body nil))
  ([content-type body principal]
   (cond-> {:request-method :post
            :uri            "/hail/send"
            :headers        {"content-type" content-type}
            :body           body}
     principal (assoc :isaac/principal principal))))

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
                  :principal   "admin"
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
                  :principal   "admin"
                  :sent-at     "2026-05-24T17:00:00Z"}
                 body))))

  (it "returns 400 naming the reader error when the record does not read back (isaac-k0xm)"
    (let [response (sut/handler (post-request "application/json"
                                              "{\"frequencies\":{\"band\":\"b\"},\"params\":{\":a/b\":1}}"))
          body     (json/parse-string (:body response) true)]
      (should= 400 (:status response))
      (should-contain "unreadable" (:error body))
      (should-not (fs/exists? (nexus/get :fs) "/test/isaac/hail/pending"))))

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
      (should= "request body could not be parsed" (:hint body))))

  (it "normalizes a string session to the same vector the CLI produces for --session"
    (let [response (sut/handler (post-request "application/json"
                                              "{\"frequencies\":{\"session\":\"isaac-work-1\"},\"prompt\":\"wake the watch\"}"))
          id       (:id (json/parse-string (:body response) true))]
      (should= 201 (:status response))
      (should= {:session [:isaac-work-1]} (:frequencies (queue/read-pending id)))))

  (it "normalizes a vector of session strings element-wise"
    (let [response (sut/handler (post-request "application/json"
                                              "{\"frequencies\":{\"session\":[\"a\",\"b\"]},\"prompt\":\"wake the watch\"}"))
          id       (:id (json/parse-string (:body response) true))]
      (should= 201 (:status response))
      (should= {:session [:a :b]} (:frequencies (queue/read-pending id)))))

  (it "returns 400 naming session when session is not a string or vector of strings"
    (let [response (sut/handler (post-request "application/json"
                                              "{\"frequencies\":{\"session\":42},\"prompt\":\"wake the watch\"}"))
          body     (json/parse-string (:body response) true)]
      (should= 400 (:status response))
      (should= "invalid session" (:error body))
      (should (.contains (str (:hint body)) "session"))))

  (it "normalizes a string session-tags value to a keyword set"
    (let [response (sut/handler (post-request "application/json"
                                              "{\"frequencies\":{\"session-tags\":\"wip\"},\"prompt\":\"wake the watch\"}"))
          id       (:id (json/parse-string (:body response) true))]
      (should= 201 (:status response))
      (should= {:session-tags #{:wip}} (:frequencies (queue/read-pending id)))))

  (it "returns 400 naming session-tags when session-tags is not a string or collection of strings"
    (let [response (sut/handler (post-request "application/json"
                                              "{\"frequencies\":{\"session-tags\":42},\"prompt\":\"wake the watch\"}"))
          body     (json/parse-string (:body response) true)]
      (should= 400 (:status response))
      (should= "invalid session-tags" (:error body))
      (should (.contains (str (:hint body)) "session-tags"))))

  (it "returns 400 naming crew when crew is not a string"
    (let [response (sut/handler (post-request "application/json"
                                              "{\"frequencies\":{\"crew\":42},\"prompt\":\"wake the watch\"}"))
          body     (json/parse-string (:body response) true)]
      (should= 400 (:status response))
      (should= "invalid crew" (:error body))
      (should (.contains (str (:hint body)) "crew"))))

  (it "records the sending principal on the persisted hail"
    (let [response (sut/handler (post-request "application/json"
                                              "{\"frequencies\":{\"band\":\"bean-pickup\"},\"params\":{\"n\":1}}"
                                              {:name :ci :scopes #{:hail/send}}))
          id       (:id (json/parse-string (:body response) true))]
      (should= 201 (:status response))
      (should= "ci" (:principal (queue/read-pending id)))))
  (it "refuses a band prompt override without hail/prompt-override and does not persist"
    (let [sent (atom nil)]
      (with-redefs [queue/send! (fn [record] (reset! sent record) record)]
        (try
          (sut/handler (post-request "application/json"
                                     "{\"frequencies\":{\"band\":\"bean-pickup\"},\"prompt\":\"custom\"}"
                                     {:name :ci :scopes #{:hail/send}}))
          (should-fail "expected forbidden")
          (catch clojure.lang.ExceptionInfo e
            (should= 403 (:status (ex-data e)))
            (should= :scope (:isaac.http/reason (ex-data e))))))
      (should-be-nil @sent)))

  (it "allows a band prompt override when the principal holds hail/prompt-override"
    (let [response (sut/handler (post-request "application/json"
                                              "{\"frequencies\":{\"band\":\"bean-pickup\"},\"prompt\":\"custom instructions\"}"
                                              {:name :ops :scopes #{:hail/send :hail/prompt-override}}))
          id       (:id (json/parse-string (:body response) true))]
      (should= 201 (:status response))
      (should= "custom instructions" (:prompt (queue/read-pending id)))
      (should= "ops" (:principal (queue/read-pending id)))))

  (it "treats a session-direct prompt as ordinary hail/send"
    (let [response (sut/handler (post-request "application/json"
                                              "{\"frequencies\":{\"session\":\"watch-room\"},\"prompt\":\"wake the watch\"}"
                                              {:name :ci :scopes #{:hail/send}}))
          id       (:id (json/parse-string (:body response) true))]
      (should= 201 (:status response))
      (should= "wake the watch" (:prompt (queue/read-pending id)))
      (should= "ci" (:principal (queue/read-pending id)))))

  (it "records admin and allows a band prompt override when wrap-auth attached no principal"
    (let [response (sut/handler (post-request "application/json"
                                              "{\"frequencies\":{\"band\":\"bean-pickup\"},\"prompt\":\"custom instructions\"}"))
          id       (:id (json/parse-string (:body response) true))]
      (should= 201 (:status response))
      (should= "custom instructions" (:prompt (queue/read-pending id)))
      (should= "admin" (:principal (queue/read-pending id)))))
  )
