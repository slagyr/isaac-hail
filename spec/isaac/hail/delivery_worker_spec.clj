(ns isaac.hail.delivery-worker-spec
  (:require
    [isaac.charge]
    [isaac.comm.null :as null-comm]
    [isaac.config.api :as config]
    [isaac.config.loader :as loader]
    [isaac.drive.turn]
    [isaac.fs :as fs]
    [isaac.hail.delivery-worker :as sut]
    [isaac.llm.api.grover :as grover]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]
    [isaac.session.store.spi :as store]
    [isaac.session.store.memory :as memory-store]
    [isaac.spec-helper :as helper]
    [speclj.core :refer :all])
  (:import
    (java.time Instant)))

(def ^:private test-config
  {:defaults {:crew "bartholomew" :model "grover"}
   :crew     {"atticus"     {:model :grover :soul "You are Atticus."}
              "bartholomew" {:model :grover :soul "You are Bartholomew."}
              "cordelia"    {:model :grover :soul "You are Cordelia."}}
   :models   {"grover" {:model "echo" :provider :grover :context-window 32768}}
   :providers {"grover" {}}})

(defn- setup-runtime [example]
  ;; Install the fs BEFORE normalizing the config: normalize-config composes the
  ;; module index from the foundation manifest, which reads via fs/instance.
  ;; Otherwise this spec only passes when a prior spec left a global fs installed
  ;; (full-suite order) and dies with "no filesystem available" when run alone.
  (nexus/-with-nexus {:fs (fs/mem-fs) :root "/test/isaac"}
    (let [session-store (memory-store/create-store "/test/isaac")
          cfg           (loader/normalize-config test-config)]
      (nexus/register! [:config] (atom cfg))
      (nexus/register! [:sessions] {:store session-store})
      (config/dangerously-install-config! cfg "spec")
      (example))))

(defn- read-edn [path]
  (some-> (fs/slurp (nexus/get :fs) path) read-string))

;; A routed delivery file IS the hail (flat — enriched in place), named by its
;; own hail id. No :hail wrapper, no separate delivery-N id.
(defn- write-delivery! [record]
  (let [path (str "/test/isaac/hail/deliveries/" (:id record) ".edn")]
    (fs/mkdirs (nexus/get :fs) "/test/isaac/hail/deliveries")
    (fs/spit (nexus/get :fs) path (pr-str record))))

(describe "hail delivery worker"

  (helper/with-captured-logs)

  #_{:clj-kondo/ignore [:invalid-arity :unresolved-symbol]}
  (around [example]
    (setup-runtime example))

  #_{:clj-kondo/ignore [:invalid-arity :unresolved-symbol]}
  (around [example]
    (grover/reset-queue!)
    (example))

  (it "dispatches a bound delivery as a hail-origin turn and moves it to delivered"
    (let [session-store (nexus/get-in [:sessions :store])
          captured      (atom nil)]
      (store/open-session! session-store "engine-room" {:crew "bartholomew"})
      (write-delivery! {:id       "hail-1"
                        :prompt   "Seal the leak."
                        :crew     :bartholomew
                        :session  :engine-room
                        :attempts 0})
      (with-redefs [isaac.charge/build         (fn [request]
                                                 (reset! captured request)
                                                 {:charge/type :charge
                                                  :session-key "engine-room"
                                                  :input       "Seal the leak."
                                                  :origin      {:kind :hail :hail-id "hail-1"}})
                    isaac.drive.turn/run-turn! (fn [_charge]
                                                 {})]
        (let [future* (first (sut/tick! {:cfg test-config :session-store session-store}))]
          (should-not-be-nil future*)
          @future*))
      (should= {:kind :hail :hail-id "hail-1" :prompt "Seal the leak."}
               (:origin @captured))
      (should= null-comm/channel (:comm @captured))
      (should= "Seal the leak." (:input @captured))
      (should= "engine-room" (:session-key @captured))
      (should-not (fs/exists? (nexus/get :fs) "/test/isaac/hail/deliveries/hail-1.edn"))
      (should= {:id       "hail-1"
                :prompt   "Seal the leak."
                :crew     :bartholomew
                :session  :engine-room
                :attempts 0}
               (read-edn "/test/isaac/hail/delivered/hail-1.edn"))))

  (it "stamps hail guidance on the dispatched charge"
    (let [session-store (nexus/get-in [:sessions :store])
          captured      (atom nil)]
      (store/open-session! session-store "engine-room" {:crew "bartholomew"})
      (with-redefs [isaac.charge/build (fn [request]
                                         (reset! captured request)
                                         {:charge/type :charge})]
        (#'sut/delivery-charge test-config {:id      "hail-1"
                                            :prompt  "Seal the leak."
                                            :session :engine-room}))
      (let [guidance (:guidance @captured)]
        (should-contain "Autonomous hail; the user may not see your reply." guidance)
        (should-contain "Session: engine-room" guidance)
        (should-contain "Hail id: hail-1" guidance))))

  (it "binds an unbound delivery to the first idle candidate"
    (let [session-store (nexus/get-in [:sessions :store])]
      (store/open-session! session-store "bridge" {:crew "atticus"})
      (store/open-session! session-store "first-watch" {:crew "cordelia"})
      (store/mark-in-flight! session-store "first-watch")
      (should= {:crew :atticus :session :bridge}
               (select-keys (#'sut/runnable-delivery test-config
                                                     session-store
                                                     {:id         "hail-1"
                                                      :prompt     "Status report?"
                                                      :candidates [{:crew :atticus :session :bridge}
                                                                   {:crew :cordelia :session :first-watch}]
                                                      :attempts   0})
                            [:crew :session]))))

  (it "spawns a sequential hail-origin session when a spawn delivery has no existing match"
    (let [session-store (nexus/get-in [:sessions :store])
          cfg           (loader/normalize-config test-config)]
      (config/dangerously-install-config! cfg "spec")
      (write-delivery! {:id       "hail-1"
                        :crew     :bartholomew
                        :prompt   "Resonance climbing."
                        :frequencies {:session-tags #{:project/warp-coil}
                                    :reach :one
                                    :create :if-missing}
                        :attempts 0})
      (with-redefs [isaac.drive.turn/run-turn! (fn [_] {})]
        @(first (sut/tick! {:cfg cfg :session-store session-store})))
      (should= {:crew "bartholomew"
                :tags #{:project/warp-coil}
                :origin {:kind :hail :hail-id "hail-1"}}
               (select-keys (store/get-session session-store "session-1") [:crew :tags :origin]))
      (should= {:crew :bartholomew
                :session "session-1"}
               (select-keys (read-edn "/test/isaac/hail/delivered/hail-1.edn") [:crew :session]))))

  (it "binds a spawn delivery to an existing matching session instead of creating one"
    (let [session-store (nexus/get-in [:sessions :store])
          cfg           (loader/normalize-config test-config)]
      (config/dangerously-install-config! cfg "spec")
      (store/open-session! session-store "coil-work" {:crew "bartholomew" :tags #{:project/warp-coil}})
      (should= {:crew :bartholomew :session :coil-work}
               (select-keys (#'sut/runnable-delivery
                             cfg
                             session-store
                             {:id       "hail-1"
                              :crew     :main
                              :prompt   "Resonance climbing."
                              :frequencies {:session-tags #{:project/warp-coil}
                                          :reach :one
                                          :create :if-missing}
                              :attempts 0})
                            [:crew :session]))
      (should-be-nil (store/get-session session-store "session-1"))))

  (it "spawns under the delivery's resolved crew when no session matches"
    (let [session-store (nexus/get-in [:sessions :store])
          cfg           (loader/normalize-config test-config)]
      (config/dangerously-install-config! cfg "spec")
      (should= {:action :spawn :crew-id "bartholomew"}
               (#'sut/spawn-target
                cfg
                session-store
                {:id       "hail-1"
                 :crew     :bartholomew
                 :prompt   "Resonance climbing."
                 :frequencies {:session-tags #{:project/warp-coil}
                             :reach :one
                             :create :if-missing}
                 :attempts 0}))))

  (it "waits on a busy matching session for a spawn delivery and does not create a sibling"
    (let [session-store (nexus/get-in [:sessions :store])
          cfg           (-> test-config
                            (assoc-in [:crew "bartholomew" :max-in-flight] 2)
                            loader/normalize-config)]
      (config/dangerously-install-config! cfg "spec")
      (store/open-session! session-store "coil-work" {:crew "bartholomew" :tags #{:project/warp-coil}})
      (store/mark-in-flight! session-store "coil-work")
      (should= nil
               (#'sut/runnable-delivery
                cfg
                session-store
                {:id       "hail-1"
                 :crew     :main
                 :prompt   "Resonance climbing."
                 :frequencies {:session-tags #{:project/warp-coil}
                             :reach :one
                             :create :if-missing}
                 :attempts 0}))
      (should-be-nil (store/get-session session-store "session-1"))))

  (it "waits when the resolved processing crew is at capacity"
    (let [session-store (nexus/get-in [:sessions :store])
          cfg           (-> test-config
                            (assoc-in [:crew "bartholomew" :max-in-flight] 1)
                            loader/normalize-config)]
      (config/dangerously-install-config! cfg "spec")
      (store/open-session! session-store "other-work" {:crew "bartholomew"})
      (store/mark-in-flight! session-store "other-work")
      (should= {:action :wait}
               (#'sut/spawn-target
                cfg
                session-store
                {:id       "hail-1"
                 :crew     :bartholomew
                 :prompt   "Resonance climbing."
                 :frequencies {:session-tags #{:project/warp-coil}
                             :reach :one
                             :create :if-missing}
                 :attempts 0}))))

  (it "leaves a delivery pending when its session is already in flight"
    (let [session-store (nexus/get-in [:sessions :store])]
      (store/open-session! session-store "engine-room" {:crew "bartholomew"})
      (store/mark-in-flight! session-store "engine-room")
      (write-delivery! {:id       "hail-1"
                        :prompt   "Seal the leak."
                        :crew     :bartholomew
                        :session  :engine-room
                        :attempts 0})
      (should= []
               (sut/tick! {:cfg test-config :session-store session-store}))
      (should= {:id       "hail-1"
                :prompt   "Seal the leak."
                :crew     :bartholomew
                :session  :engine-room
                :attempts 0}
               (read-edn "/test/isaac/hail/deliveries/hail-1.edn"))
      (should-not (fs/exists? (nexus/get :fs) "/test/isaac/hail/inflight/hail-1.edn"))))

  (it "leaves a delivery pending when its crew is at capacity"
    (let [session-store (nexus/get-in [:sessions :store])
          cfg           (assoc-in test-config [:crew "bartholomew" :max-in-flight] 1)
          cfg           (loader/normalize-config cfg)]
      (config/dangerously-install-config! cfg "spec")
      (store/open-session! session-store "engine-room" {:crew "bartholomew"})
      (store/open-session! session-store "warp-core" {:crew "bartholomew"})
      (store/mark-in-flight! session-store "warp-core")
      (write-delivery! {:id       "hail-1"
                        :prompt   "Check the core."
                        :crew     :bartholomew
                        :session  :engine-room
                        :attempts 0})
      (should= []
               (sut/tick! {:cfg cfg :session-store session-store}))
      (should= {:id       "hail-1"
                :prompt   "Check the core."
                :crew     :bartholomew
                :session  :engine-room
                :attempts 0}
               (read-edn "/test/isaac/hail/deliveries/hail-1.edn"))))

  (it "reschedules a failed turn with the next backoff and clears in-flight"
    (let [session-store (nexus/get-in [:sessions :store])]
      (store/open-session! session-store "engine-room" {:crew "bartholomew"})
      (write-delivery! {:id       "hail-1"
                        :prompt   "Seal the leak."
                        :crew     :bartholomew
                        :session  :engine-room
                        :attempts 0})
      (with-redefs [isaac.drive.turn/run-turn! (fn [_] {:error :api-error})]
        @(first (sut/tick! {:cfg           test-config
                            :now           (Instant/parse "2026-04-21T10:00:00Z")
                            :session-store session-store})))
      (should= {:attempts        1
                :next-attempt-at "2026-04-21T10:00:01Z"}
               (select-keys (read-edn "/test/isaac/hail/deliveries/hail-1.edn")
                            [:attempts :next-attempt-at]))
      (should-not (store/in-flight? session-store "engine-room"))
      (should-not (fs/exists? (nexus/get :fs) "/test/isaac/hail/inflight/hail-1.edn"))))

  (it "moves exhausted deliveries to failed and logs dead-lettering"
    (let [session-store (nexus/get-in [:sessions :store])]
      (store/open-session! session-store "engine-room" {:crew "bartholomew"})
      (write-delivery! {:id       "hail-1"
                        :prompt   "Seal the leak."
                        :crew     :bartholomew
                        :session  :engine-room
                        :attempts 4})
      (with-redefs [isaac.drive.turn/run-turn! (fn [_] {:error :api-error})]
        @(first (sut/tick! {:cfg           test-config
                            :now           (Instant/parse "2026-04-21T10:00:00Z")
                            :session-store session-store})))
      (should-not (fs/exists? (nexus/get :fs) "/test/isaac/hail/deliveries/hail-1.edn"))
      (should= {:attempts 5}
               (select-keys (read-edn "/test/isaac/hail/failed/hail-1.edn") [:attempts]))
      (should= {:event :hail/dead-lettered :id "hail-1" :reason :exhausted}
               (select-keys (last @log/captured-logs) [:event :id :reason]))))

  (it "registers the shared scheduler task"
    (let [shared-scheduler (scheduler/create {})]
      (try
        (nexus/register! [:scheduler] shared-scheduler)
        (let [started (sut/start! {})]
          (should= :hail/deliver (:task-id started))
          (should= [{:id :hail/deliver :trigger {:kind :interval :ms 1000}}]
                   (mapv #(select-keys % [:id :trigger]) (scheduler/list-tasks shared-scheduler)))
          (sut/stop! started))
        (finally
          (scheduler/shutdown! shared-scheduler))))))
