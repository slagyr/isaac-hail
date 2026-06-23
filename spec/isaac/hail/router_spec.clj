(ns isaac.hail.router-spec
  (:require
    [isaac.fs :as fs]
    [isaac.hail.router :as sut]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]
    [isaac.session.store.spi :as store]
    [isaac.session.store.memory :as memory]
    [speclj.core :refer :all]))

(def ^:private test-cfg
  {:defaults {:crew "main"}})

(describe "hail router"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (example)))

  (it "binds a reach-one band to the only matching session"
    (let [result (sut/resolve-obligations test-cfg
                                          {"engineering-intercom" {:session-tags #{:role/engineer} :reach :one}}
                                          [{:id "engine-room" :crew "bartholomew" :tags #{:role/engineer}}]
                                          {:id "hail-1" :frequency {:band "engineering-intercom"}})]
      (should= {:deliveries [{:hail     {:id "hail-1" :frequency {:band "engineering-intercom"}}
                              :crew     :bartholomew
                              :session  :engine-room
                              :attempts 0}]}
               result)))

  (it "leaves a reach-one pool unbound with sorted candidates"
    (let [result (sut/resolve-obligations test-cfg
                                          {}
                                          [{:id "first-watch" :crew "cordelia" :tags #{:role/command}}
                                           {:id "bridge" :crew "atticus" :tags #{:role/command}}]
                                          {:id        "hail-1"
                                           :frequency {:session-tags #{:role/command}}
                                           :reach     :one})]
      (should= {:deliveries [{:hail       {:id        "hail-1"
                                           :frequency {:session-tags #{:role/command}}
                                           :reach     :one}
                              :crew       nil
                              :session    nil
                              :candidates [{:crew :atticus :session :bridge}
                                           {:crew :cordelia :session :first-watch}]
                              :attempts   0}]}
               result)))

  (it "uses a hail processing-crew override over the matched session crew"
    (let [result (sut/resolve-obligations test-cfg
                                          {}
                                          [{:id "engine-room" :crew "bartholomew"}]
                                          {:id        "hail-1"
                                           :crew      :marvin
                                           :frequency {:session [:engine-room]}})]
      (should= {:deliveries [{:hail     {:id        "hail-1"
                                         :crew      :marvin
                                         :frequency {:session [:engine-room]}}
                              :crew     :marvin
                              :session  :engine-room
                              :attempts 0}]}
               result)))

  (it "emits a spawn delivery with resolved crew when no session matches"
    (let [hail   {:id "hail-1"
                  :frequency {:session-tags #{:project/warp-coil}
                              :reach :one
                              :spawn-session true}}
          result (sut/resolve-obligations test-cfg {} [] hail)]
      (should= {:deliveries [{:hail hail :crew :main :session nil :attempts 0}]}
               result)))

  (it "returns unknown-band when the referenced band is missing"
    (let [result (sut/resolve-obligations test-cfg
                                          {}
                                          []
                                          {:id "hail-1" :frequency {:band "phantom-band"}})]
      (should= {:undeliverable {:hail   {:id "hail-1" :frequency {:band "phantom-band"}}
                                :reason :unknown-band}}
               result)))

  (it "fans out reach-all matches in session order"
    (let [result (sut/resolve-obligations test-cfg
                                          {}
                                          [{:id "first-watch" :crew "cordelia" :tags #{:role/command}}
                                           {:id "bridge" :crew "atticus" :tags #{:role/command}}]
                                          {:id        "hail-1"
                                           :frequency {:session-tags #{:role/command}}
                                           :reach     :all})]
      (should= {:deliveries [{:hail     {:id        "hail-1"
                                         :frequency {:session-tags #{:role/command}}
                                         :reach     :all}
                              :crew     :atticus
                              :session  :bridge
                              :attempts 0}
                             {:hail     {:id        "hail-1"
                                         :frequency {:session-tags #{:role/command}}
                                         :reach     :all}
                              :crew     :cordelia
                              :session  :first-watch
                              :attempts 0}]}
               result)))

  (it "writes deliveries and removes the pending hail on tick"
    (let [session-store (memory/create-store)]
      (store/open-session! session-store "engine-room" {:crew "bartholomew"})
      (fs/mkdirs (nexus/get :fs) "/test/isaac/hail/pending")
      (fs/spit (nexus/get :fs) "/test/isaac/hail/pending/hail-1.edn"
               (pr-str {:id "hail-1" :frequency {:session [:engine-room]} :from :cli}))
      (sut/tick! {:cfg           {:defaults {:crew "main"}
                                  :crew {:bartholomew {:model "grover"}}}
                  :session-store session-store})
      (should-not (fs/exists? (nexus/get :fs) "/test/isaac/hail/pending/hail-1.edn"))
      (should= {:id       "delivery-1"
                :hail     {:id "hail-1" :frequency {:session [:engine-room]} :from :cli}
                :crew     :bartholomew
                :session  :engine-room
                :attempts 0}
               (read-string (fs/slurp (nexus/get :fs) "/test/isaac/hail/deliveries/delivery-1.edn")))))

  (it "moves no-recipient hails to undeliverable on tick"
    (let [session-store (memory/create-store)]
      (fs/mkdirs (nexus/get :fs) "/test/isaac/hail/pending")
      (fs/spit (nexus/get :fs) "/test/isaac/hail/pending/hail-1.edn"
               (pr-str {:id "hail-1" :frequency {:session-tags #{:role/command}} :from :cli}))
      (sut/tick! {:cfg           {:crew {:bartholomew {:tags #{:role/engineer}}}}
                  :session-store session-store})
      (should-not (fs/exists? (nexus/get :fs) "/test/isaac/hail/pending/hail-1.edn"))
      (should= {:hail   {:id "hail-1" :frequency {:session-tags #{:role/command}} :from :cli}
                :reason :no-recipients}
               (read-string (fs/slurp (nexus/get :fs) "/test/isaac/hail/undeliverable/hail-1.edn")))))

  (it "registers the shared scheduler task"
    (let [scheduler (scheduler/create {})]
      (try
        (nexus/register! [:scheduler] scheduler)
        (let [started (sut/start! {})]
          (should= :hail/route (:task-id started))
          (should= [{:id :hail/route :trigger {:kind :interval :ms 1000}}]
                   (mapv #(select-keys % [:id :trigger]) (scheduler/list-tasks scheduler)))
          (sut/stop! started))
        (finally
          (scheduler/shutdown! scheduler))))))