(ns isaac.hail.comm-spec
  (:require
    [isaac.comm.protocol :as comm]
    [isaac.hail.comm :as sut]
    [speclj.core :refer :all]))

(describe "hail comm"

  (before (sut/clear-events!))

  (it "answers :wrap-up when the cycle budget is exhausted"
    (should= :wrap-up (comm/on-exhausted sut/channel "engine-room" {:cycle-limit 1})))

  (it "keeps send as a non-transient no-op"
    (should= {:ok false :transient? false} (comm/send! sut/channel {:content "ignored"})))

  (it "records bulletins so attention is observable"
    (comm/on-bulletin sut/channel "engine-room"
                      {:kind :hail/continuations-exhausted
                       :text "hail-1 continuations exhausted"})
    (should= [{:event   "bulletin"
               :session "engine-room"
               :kind    "hail/continuations-exhausted"
               :text    "hail-1 continuations exhausted"}]
             @sut/events))
  )
