(ns isaac.tool.hail-spec
  (:require
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.hail.queue :as queue]
    [isaac.nexus :as nexus]
    [isaac.session.spec-helper :as helper]
    [isaac.tool.hail :as sut]
    [speclj.core :refer :all]))

(describe "hail tool"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (helper/with-memory-store
        (try
          (example)
          (finally
            (loader/set-snapshot! nil "spec"))))))

  (it "sends a hail from the calling crew and returns the hail id"
    (helper/create-session! "/test/isaac" "work-sess" {:crew "main"})
    (let [sent* (atom nil)]
      (with-redefs [queue/send! (fn [record]
                                  (reset! sent* record)
                                  (assoc record :id "hail-1"))]
        (let [result (sut/hail-send-tool {"session_key" "work-sess"
                                          "band"        "bean-pickup"
                                          "params"      {"n" 1}})]
          (should= "hail-1" (:result result))
          (should= {:frequencies {:band "bean-pickup"}
                    :params      {"n" 1}
                    :from        :crew/main}
                   @sent*)))))

  (it "normalizes JSON-shaped frequency keys before persisting"
    (helper/create-session! "/test/isaac" "work-sess" {:crew "main"})
    (let [sent* (atom nil)]
      (with-redefs [queue/send! (fn [record]
                                  (reset! sent* record)
                                  (assoc record :id "hail-1"))]
        (sut/hail-send-tool {"session_key"  "work-sess"
                             "band"         "bean-pickup"
                             "session_tags" ["project/warp-coil"]
                             "params"       {"n" 1}})
        (should= {:frequencies {:band "bean-pickup" :session-tags #{:project/warp-coil}}
                  :params      {"n" 1}
                  :from        :crew/main}
                 @sent*))))

  (it "accepts session_tags as an EDN set (feature table shape)"
    (helper/create-session! "/test/isaac" "work-sess" {:crew "main"})
    (let [sent* (atom nil)]
      (with-redefs [queue/send! (fn [record]
                                  (reset! sent* record)
                                  (assoc record :id "hail-1"))]
        (sut/hail-send-tool {"session_key"  "work-sess"
                             "band"         "engineering-intercom"
                             "session_tags" #{:project/warp-coil}})
        (should= {:frequencies {:band "engineering-intercom"
                                :session-tags #{:project/warp-coil}}
                  :from        :crew/main}
                 @sent*))))

  (it "accepts thread_id and reply_to in snake_case"
    (helper/create-session! "/test/isaac" "work-sess" {:crew "main"})
    (let [sent* (atom nil)]
      (with-redefs [queue/send! (fn [record]
                                  (reset! sent* record)
                                  (assoc record :id "hail-1"))]
        (sut/hail-send-tool {"session_key" "work-sess"
                             "band"        "bean-pickup"
                             "thread_id"   "thread-7"
                             "reply_to"    "hail-42"})
        (should= {:frequencies {:band "bean-pickup"}
                  :thread-id   "thread-7"
                  :reply-to    "hail-42"
                  :from        :crew/main}
                 @sent*))))

  (it "errors when no addressing field is provided"
    (helper/create-session! "/test/isaac" "work-sess" {:crew "main"})
    (let [result (sut/hail-send-tool {"session_key" "work-sess"})]
      (should (:isError result))
      (should= "At least one addressing field is required (band, session, session_tags, or crew)"
               (:error result))))

  (it "errors when the session does not exist"
    (let [result (sut/hail-send-tool {"session_key" "missing"
                                      "band"        "bean-pickup"})]
      (should (:isError result))
      (should= "session not found: missing" (:error result))))

  (it "errors when an explicit session id names no existing session"
    (helper/create-session! "/test/isaac" "work-sess" {:crew "main"})
    (let [sent* (atom false)]
      (with-redefs [queue/send! (fn [_] (reset! sent* true) {:id "hail-1"})]
        (let [result (sut/hail-send-tool {"session_key" "work-sess"
                                          "session"     "first-watch"
                                          "params"      {"bean-id" "x"}})]
          (should (:isError result))
          (should (re-find #"no session \"first-watch\"" (:error result)))
          (should-not @sent*)))))

  (it "errors when an explicit session id equals a configured band name"
    (helper/create-session! "/test/isaac" "work-sess" {:crew "main"})
    (loader/set-snapshot! {:hail {"engineering-intercom" {:session-tags #{:project/warp-coil}
                                                          :reach        :one}}}
                          "spec")
    (let [sent* (atom false)]
      (with-redefs [queue/send! (fn [_] (reset! sent* true) {:id "hail-1"})]
        (let [result (sut/hail-send-tool {"session_key" "work-sess"
                                          "session"     "engineering-intercom"
                                          "params"      {"bean-id" "x"}})]
          (should (:isError result))
          (should (re-find #"engineering-intercom.*band.*not a session" (:error result)))
          (should-not @sent*)))))

  (it "sends when an explicit session id exists"
    (helper/create-session! "/test/isaac" "work-sess" {:crew "main"})
    (helper/create-session! "/test/isaac" "engine-room" {:crew "bartholomew"})
    (let [sent* (atom nil)]
      (with-redefs [queue/send! (fn [record]
                                  (reset! sent* record)
                                  (assoc record :id "hail-1"))]
        (let [result (sut/hail-send-tool {"session_key" "work-sess"
                                          "session"     "engine-room"
                                          "params"      {"bean-id" "x"}})]
          (should= "hail-1" (:result result))
          (should= {:frequencies {:session ["engine-room"]}
                    :params      {"bean-id" "x"}
                    :from        :crew/main}
                   @sent*)))))

  (it "does not reject a missing explicit session when create is if-missing"
    (helper/create-session! "/test/isaac" "work-sess" {:crew "main"})
    (let [sent* (atom nil)]
      (with-redefs [queue/send! (fn [record]
                                  (reset! sent* record)
                                  (assoc record :id "hail-1"))]
        (let [result (sut/hail-send-tool {"session_key" "work-sess"
                                          "session"     "first-watch"
                                          "create"      "if-missing"
                                          "params"      {"bean-id" "x"}})]
          (should= "hail-1" (:result result))
          (should= "first-watch" (first (:session (:frequencies @sent*))))))))

  (it "exposes flat snake_case properties borrowed from the frequencies schema"
    (let [factory (:parameters (sut/hail-send-tool-factory nil))
          props   (:properties factory)]
      (should-not (contains? props "frequencies"))
      (should (contains? props "band"))
      (should (contains? props "session_tags"))
      (should (contains? props "with_crew"))
      (should (contains? props "params"))
      (should-not (contains? props "payload"))
      (should= "object" (get-in props ["params" :type]))
      (should-not (re-find #"EDN" (str (get-in props ["params" :description]))))
      (should= "Sessions whose :crew matches this id"
               (get-in props ["crew" :description])))))
