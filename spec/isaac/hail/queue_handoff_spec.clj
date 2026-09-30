(ns isaac.hail.queue-handoff-spec
  (:require
    [isaac.foundation.config.loader :as loader]
    [isaac.foundation.fs :as fs]
    [isaac.hail.queue :as sut]
    [isaac.foundation.nexus :as nexus]
    [isaac.agent.turn.queue :as turns]
    [isaac.agent.turn.submit :as submit]
    [speclj.core :refer :all]))

(describe "Stateless hail submission"
  (around [example]
    (nexus/-with-nested-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (example)))

  (it "submits a band template to Agent with its data and turn identity"
    (with-redefs [loader/snapshot (fn [_] {:hail {"engine" {:prompt "Fix {{coil}}"
                                                             :session-tags #{:project/warp}
                                                             :data {:sector "aft"}}}})
                  submit/submit! (fn [request] (assoc request :input (:prompt request) :state :queued))]
      (let [turn (sut/send! {:frequencies {:band "engine"} :params {:coil "primary"} :from :cli})]
        (should (re-matches #"[a-f0-9]{8}" (:id turn)))
        (should= "Fix primary" (:input turn))
        (should= #{:project/warp} (get-in turn [:frequencies :session-tags]))
        (should= (:id turn) (get-in turn [:origin :thread-id]))
        (should= {:sector "aft" :coil "primary"} (get-in turn [:origin :data]))
        (should-not (fs/exists? (nexus/get :fs) "/test/isaac/hail")))))

  (it "reuses the parent turn's thread when replying"
    (with-redefs [loader/snapshot (fn [_] {:hail {"engine" {:prompt "Fix leak" :crew "bartholomew"}}})
                  turns/read-held (fn [_] {:origin {:thread-id "thread-1"}})
                  submit/submit! (fn [request] (assoc request :id "turn-2"))]
      (should= "thread-1"
               (get-in (sut/send! {:frequencies {:band "engine"} :reply-to "turn-1" :from :cli})
                       [:origin :thread-id]))))

  (it "rejects an unknown band without submitting a turn"
    (with-redefs [loader/snapshot (fn [_] {:hail {}})
                  submit/submit! (fn [_] (throw (ex-info "submitted" {})))]
      (should-throw Exception #"unknown band: missing"
        (sut/send! {:frequencies {:band "missing"} :from :cli}))))
  )
