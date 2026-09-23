(ns isaac.hail.repro-bsqm-spec
  "isaac-bsqm repro: the hail runtime component on a REAL scheduler, the way the
   server wires it — not with router/start! and delivery-worker/start! stubbed."
  (:require
    [isaac.component.factory :as component-factory]
    [isaac.component.protocol :as component]
    [isaac.config.api :as config]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.hail.component]
    [isaac.hail.delivery-worker :as worker]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]
    [isaac.session.store.memory :as memory-store]
    [isaac.session.store.spi :as store]
    [speclj.core :refer :all]))

(def ^:private test-config
  {:defaults  {:frequencies {:crew "bartholomew"} :crew {:model "grover"}}
   :crew      {"bartholomew" {:model :grover :soul "You are Bartholomew."}}
   :models    {"grover" {:model "echo" :provider :grover :context-window 32768}}
   :providers {"grover" {}}})

(describe "isaac-bsqm: hail runtime on a real scheduler"

  (it "registers both hail tasks and ticks the delivery worker"
    (nexus/-with-nexus {:fs (fs/mem-fs) :root "/test/isaac"}
      (let [session-store (memory-store/create-store "/test/isaac")
            cfg           (loader/normalize-config test-config)
            sched         (-> (scheduler/create {}) scheduler/start!)
            ticks         (atom 0)]
        (nexus/register! [:config] (atom cfg))
        (nexus/register! [:sessions] {:store session-store})
        (nexus/register! [:scheduler] sched)
        (config/dangerously-install-config! cfg "spec")
        (store/open-session! session-store "engine-room" {:crew "bartholomew"})
        (fs/mkdirs (nexus/get :fs) "/test/isaac/hail/deliveries")
        (fs/spit (nexus/get :fs) "/test/isaac/hail/deliveries/hail-1.edn"
                 (pr-str {:id "hail-1" :prompt "Seal the leak." :crew :bartholomew
                          :bound-session :engine-room :attempts 0}))
        (try
          (let [instance (component-factory/create :hail-runtime {})]
            (with-redefs [worker/tick! (fn [& _] (swap! ticks inc))]
              (component/start instance)
              (should= #{:hail/route :hail/deliver}
                       (set (map :id (scheduler/list-tasks sched))))
              (Thread/sleep 2500)
              (component/stop instance))
            (should (pos? @ticks)))
          (finally (scheduler/shutdown! sched)))))))
