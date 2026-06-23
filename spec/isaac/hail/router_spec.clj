(ns isaac.hail.router-spec
  (:require
    [isaac.fs :as fs]
    [isaac.hail.router :as sut]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]
    [isaac.session.store.spi :as store]
    [isaac.session.store.memory :as memory]
    [speclj.core :refer :all]))

(describe "hail router"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (example)))

  (it "binds a reach-one band to the only matching session"
    (let [result (sut/resolve-obligations {"engineering-intercom" {:crew-tags #{:role/engineer} :reach :one}}
                                          {:bartholomew {:tags #{:role/engineer}}}
                                          [{:id "engine-room" :crew "bartholomew"}]
                                          {:id "hail-1" :frequency {:band "engineering-intercom"}})]
      (should= {:deliveries [{:hail     {:id "hail-1" :frequency {:band "engineering-intercom"}}
                              :crew     :bartholomew
                              :session  :engine-room
                              :attempts 0}]}
               result)))

  (it "leaves a reach-one pool unbound with sorted candidates"
    (let [result (sut/resolve-obligations {}
                                          {:atticus  {:tags #{:role/command}}
                                           :cordelia {:tags #{:role/command}}}
                                          [{:id "first-watch" :crew "cordelia"}
                                           {:id "bridge" :crew "atticus"}]
                                          {:id        "hail-1"
                                           :frequency {:crew-tags #{:role/command}}
                                           :reach     :one})]
      (should= {:deliveries [{:hail       {:id        "hail-1"
                                           :frequency {:crew-tags #{:role/command}}
                                           :reach     :one}
                              :crew       nil
                              :session    nil
                              :candidates [{:crew :atticus :session :bridge}
                                           {:crew :cordelia :session :first-watch}]
                              :attempts   0}]}
               result)))

  (it "emits an unbound spawn delivery when a reach-one spawn hail has a host crew but no session"
    (let [hail   {:id "hail-1"
                  :frequency {:crew-tags #{:role/engineer}
                              :session-tags #{:project/warp-coil}
                              :reach :one
                              :spawn-session true}}
          result (sut/resolve-obligations {}
                                          {:bartholomew {:tags #{:role/engineer}}}
                                          []
                                          hail)]
      (should= {:deliveries [{:hail hail :crew nil :session nil :attempts 0}]}
               result)))

  (it "returns no-host when a spawn hail has no resolvable host crew"
    (let [hail   {:id "hail-1"
                  :frequency {:session-tags #{:project/warp-coil}
                              :reach :one
                              :spawn-session true}}
          result (sut/resolve-obligations {}
                                          {}
                                          []
                                          hail)]
      (should= {:undeliverable {:hail hail :reason :no-host}}
               result)))

  (it "returns unknown-band when the referenced band is missing"
    (let [result (sut/resolve-obligations {}
                                          {}
                                          []
                                          {:id "hail-1" :frequency {:band "phantom-band"}})]
      (should= {:undeliverable {:hail   {:id "hail-1" :frequency {:band "phantom-band"}}
                                :reason :unknown-band}}
               result)))

  (it "fans out reach-all matches in session order"
    (let [result (sut/resolve-obligations {}
                                          {:atticus  {:tags #{:role/command}}
                                           :cordelia {:tags #{:role/command}}}
                                          [{:id "first-watch" :crew "cordelia"}
                                           {:id "bridge" :crew "atticus"}]
                                          {:id        "hail-1"
                                           :frequency {:crew-tags #{:role/command}}
                                           :reach     :all})]
      (should= {:deliveries [{:hail     {:id        "hail-1"
                                         :frequency {:crew-tags #{:role/command}}
                                         :reach     :all}
                              :crew     :atticus
                              :session  :bridge
                              :attempts 0}
                             {:hail     {:id        "hail-1"
                                         :frequency {:crew-tags #{:role/command}}
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
               (pr-str {:id "hail-1" :frequency {:crew [:bartholomew]} :from :cli}))
      (sut/tick! {:cfg           {:crew {:bartholomew {:model "grover"}}}
                  :session-store session-store})
      (should-not (fs/exists? (nexus/get :fs) "/test/isaac/hail/pending/hail-1.edn"))
      (should= {:id       "delivery-1"
                :hail     {:id "hail-1" :frequency {:crew [:bartholomew]} :from :cli}
                :crew     :bartholomew
                :session  :engine-room
                :attempts 0}
               (read-string (fs/slurp (nexus/get :fs) "/test/isaac/hail/deliveries/delivery-1.edn")))))

  (it "moves no-recipient hails to undeliverable on tick"
    (let [session-store (memory/create-store)]
      (fs/mkdirs (nexus/get :fs) "/test/isaac/hail/pending")
      (fs/spit (nexus/get :fs) "/test/isaac/hail/pending/hail-1.edn"
               (pr-str {:id "hail-1" :frequency {:crew-tags #{:role/command}} :from :cli}))
      (sut/tick! {:cfg           {:crew {:bartholomew {:tags #{:role/engineer}}}}
                  :session-store session-store})
      (should-not (fs/exists? (nexus/get :fs) "/test/isaac/hail/pending/hail-1.edn"))
      (should= {:hail   {:id "hail-1" :frequency {:crew-tags #{:role/command}} :from :cli}
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
