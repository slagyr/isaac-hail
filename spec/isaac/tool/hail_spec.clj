(ns isaac.tool.hail-spec
  (:require
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
        (example))))

  (it "sends a hail from the calling crew and returns the hail id"
    (helper/create-session! "/test/isaac" "work-sess" {:crew "main"})
    (let [sent* (atom nil)]
      (with-redefs [queue/send! (fn [record]
                                  (reset! sent* record)
                                  (assoc record :id "hail-1"))]
        (let [result (sut/hail-send-tool {"session_key" "work-sess"
                                          "band"        "bean-pickup"
                                          "payload"     {"n" 1}})]
          (should= "hail-1" (:result result))
          (should= {:frequencies {:band "bean-pickup"}
                    :payload   {"n" 1}
                    :from      :crew/main}
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
                             "payload"      {"n" 1}})
        (should= {:frequencies {:band "bean-pickup" :session-tags #{:project/warp-coil}}
                  :payload   {"n" 1}
                  :from      :crew/main}
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
    (let [result (sut/hail-send-tool {"session_key" "work-sess"
                                      "payload"     {:n 1}})]
      (should (:isError result))
      (should= "At least one addressing field is required (band, session, session_tags, or crew)"
               (:error result))))

  (it "errors when the session does not exist"
    (let [result (sut/hail-send-tool {"session_key" "missing"
                                      "band"        "bean-pickup"})]
      (should (:isError result))
      (should= "session not found: missing" (:error result))))

  (it "exposes flat snake_case properties borrowed from the frequencies schema"
    (let [factory (:parameters (sut/hail-send-tool-factory nil))
          props   (:properties factory)]
      (should-not (contains? props "frequencies"))
      (should (contains? props "band"))
      (should (contains? props "session_tags"))
      (should (contains? props "with_crew"))
      (should (contains? props "params"))
      (should= "object" (get-in props ["params" :type]))
      (should-not (re-find #"EDN" (str (get-in props ["params" :description]))))
      (should= "Sessions whose :crew matches this id"
               (get-in props ["crew" :description])))))