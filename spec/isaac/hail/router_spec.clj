(ns isaac.hail.router-spec
  (:require
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.hail.router :as sut]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]
    [isaac.session.store.spi :as store]
    [isaac.session.store.memory :as memory]
    [isaac.spec-helper :as helper]
    [speclj.core :refer :all]))

(def ^:private short-uuid-re #"^[0-9a-f]{8}$")

(defn- short-uuid? [s]
  (and (string? s) (re-matches short-uuid-re s)))

(def ^:private test-cfg
  {:defaults {:crew "main"}})

(describe "hail router"

  (helper/with-captured-logs)

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (example)))

  (it "binds a reach-one band to the only matching session, enriching the hail in place"
    (let [result (sut/resolve-obligations test-cfg
                                          {"engineering-intercom" {:session-tags #{:role/engineer} :reach :one}}
                                          [{:id "engine-room" :crew "bartholomew" :tags #{:role/engineer}}]
                                          {:id "hail-1" :frequencies {:band "engineering-intercom"}})]
      (should= {:delivery {:id          "hail-1"
                           :frequencies {:band         "engineering-intercom"
                                         :session-tags [:role/engineer]
                                         :reach        :one}
                           :crew        :bartholomew
                           :bound-session :engine-room
                           :attempts    0}}
               result)))

  (it "leaves a reach-one pool unbound with sorted candidates"
    (let [result (sut/resolve-obligations test-cfg
                                          {}
                                          [{:id "first-watch" :crew "cordelia" :tags #{:role/command}}
                                           {:id "bridge" :crew "atticus" :tags #{:role/command}}]
                                          {:id        "hail-1"
                                           :frequencies {:session-tags #{:role/command}}
                                           :reach     :one})]
      (should= {:delivery {:id          "hail-1"
                           :frequencies {:session-tags [:role/command]
                                         :reach        :one}
                           :reach       :one
                           :crew        nil
                           :bound-session nil
                           :candidates  [{:crew :atticus :session :bridge}
                                         {:crew :cordelia :session :first-watch}]
                           :attempts    0}}
               result)))

  (it "selects sessions whose :crew matches a frequency :crew selector"
    (let [result (sut/resolve-obligations test-cfg
                                          {}
                                          [{:id "agile-voyage" :crew "main"}
                                           {:id "side-job" :crew "marvin"}]
                                          {:id        "hail-1"
                                           :frequencies {:crew "main"}
                                           :reach     :one
                                           :prompt    "Work"})]
      (should= {:delivery {:id          "hail-1"
                           :frequencies {:crew  :main
                                         :reach :one}
                           :reach       :one
                           :prompt      "Work"
                           :crew        :main
                           :bound-session :agile-voyage
                           :attempts    0}}
               result)))

  (it "returns no-recipients when no session selector is present"
    (let [result (sut/resolve-obligations test-cfg
                                          {}
                                          [{:id "engine-room" :crew "bartholomew"}]
                                          {:id "hail-1" :frequencies {:reach :one}})]
      (should= {:undeliverable {:id "hail-1" :frequencies {:reach :one} :reason :no-recipients}}
               result)))

  (it "emits a spawn delivery with resolved crew when no session matches"
    (let [hail   {:id "hail-1"
                  :frequencies {:session-tags #{:project/warp-coil}
                              :reach :one
                              :create :if-missing}}
          result (sut/resolve-obligations test-cfg {} [] hail)]
      (should= {:delivery (assoc hail
                                 :frequencies {:session-tags [:project/warp-coil]
                                               :reach        :one
                                               :create       :if-missing}
                                 :crew        :main
                                 :bound-session nil
                                 :attempts    0)}
               result)))

  (it "returns unknown-band when the referenced band is missing"
    (let [result (sut/resolve-obligations test-cfg
                                          {}
                                          []
                                          {:id "hail-1" :frequencies {:band "phantom-band"}})]
      (should= {:undeliverable {:id "hail-1" :frequencies {:band "phantom-band"} :reason :unknown-band}}
               result)))

  (it "fans reach-all into a broadcast parent plus per-session children in session order"
    (let [result (sut/resolve-obligations test-cfg
                                          {}
                                          [{:id "first-watch" :crew "cordelia" :tags #{:role/command}}
                                           {:id "bridge" :crew "atticus" :tags #{:role/command}}]
                                          {:id        "hail-1"
                                           :frequencies {:session-tags #{:role/command}}
                                           :reach     :all})]
      (should= {:broadcast {:parent   {:id "hail-1" :frequencies {:session-tags #{:role/command}} :reach :all}
                            :children [{:crew :atticus :session :bridge}
                                       {:crew :cordelia :session :first-watch}]}}
               result)))

  (it "writes a flat delivery keeping the hail id and removes the pending hail on tick"
    (let [session-store (memory/create-store)]
      (store/open-session! session-store "engine-room" {:crew "bartholomew"})
      (fs/mkdirs (nexus/get :fs) "/test/isaac/hail/pending")
      (fs/spit (nexus/get :fs) "/test/isaac/hail/pending/hail-1.edn"
               (pr-str {:id "hail-1" :frequencies {:session [:engine-room]} :from :cli}))
      (sut/tick! {:cfg           {:defaults {:crew "main"}
                                  :crew {:bartholomew {:model "grover"}}}
                  :session-store session-store})
      (should-not (fs/exists? (nexus/get :fs) "/test/isaac/hail/pending/hail-1.edn"))
      (should= {:id          "hail-1"
                :frequencies {:session [:engine-room]
                              :reach   :one}
                :from        :cli
                :crew        :bartholomew
                :bound-session :engine-room
                :attempts    0}
               (read-string (fs/slurp (nexus/get :fs) "/test/isaac/hail/deliveries/hail-1.edn")))))

  (it "logs :hail/routed with the bound session outcome on tick"
    (let [session-store (memory/create-store)]
      (store/open-session! session-store "engine-room" {:crew "bartholomew"})
      (fs/mkdirs (nexus/get :fs) "/test/isaac/hail/pending")
      (fs/spit (nexus/get :fs) "/test/isaac/hail/pending/hail-1.edn"
               (pr-str {:id "hail-1" :thread-id "thread-9"
                        :frequencies {:session [:engine-room]} :from :cli}))
      (sut/tick! {:cfg           {:defaults {:crew "main"} :crew {:bartholomew {:model "grover"}}}
                  :session-store session-store})
      (let [routed (some #(when (= :hail/routed (:event %)) %) @log/captured-logs)]
        (should= {:event :hail/routed :id "hail-1" :thread-id "thread-9"
                  :outcome :delivery :session :engine-room}
                 (select-keys routed [:event :id :thread-id :outcome :session])))))

  (it "logs :hail/undeliverable when no session matches"
    (let [session-store (memory/create-store)]
      (fs/mkdirs (nexus/get :fs) "/test/isaac/hail/pending")
      (fs/spit (nexus/get :fs) "/test/isaac/hail/pending/hail-1.edn"
               (pr-str {:id "hail-1" :thread-id "thread-9"
                        :frequencies {:session [:ghost-session]} :from :cli}))
      (sut/tick! {:cfg {:defaults {:crew "main"}} :session-store session-store})
      (let [undeliverable (some #(when (= :hail/undeliverable (:event %)) %) @log/captured-logs)]
        (should= {:level :warn :event :hail/undeliverable :id "hail-1" :thread-id "thread-9" :reason :no-recipients}
                 (select-keys undeliverable [:level :event :id :thread-id :reason])))))

  (it "writes a broadcast parent plus child delivery hails on tick for reach :all"
    (let [session-store (memory/create-store)]
      (store/open-session! session-store "bridge" {:crew "atticus" :tags #{:role/command}})
      (store/open-session! session-store "first-watch" {:crew "cordelia" :tags #{:role/command}})
      (fs/mkdirs (nexus/get :fs) "/test/isaac/hail/pending")
      (fs/spit (nexus/get :fs) "/test/isaac/hail/pending/hail-1.edn"
               (pr-str {:id "hail-1" :frequencies {:session-tags #{:role/command}} :reach :all :from :cli}))
      (sut/tick! {:cfg           {:crew {:atticus {:tags #{:role/command}}
                                         :cordelia {:tags #{:role/command}}}}
                  :session-store session-store})
      (should-not (fs/exists? (nexus/get :fs) "/test/isaac/hail/pending/hail-1.edn"))
      (let [parent    (read-string (fs/slurp (nexus/get :fs) "/test/isaac/hail/broadcasts/hail-1.edn"))
            child-ids (:children parent)
            children  (mapv #(read-string (fs/slurp (nexus/get :fs)
                                                      (str "/test/isaac/hail/deliveries/" % ".edn")))
                            child-ids)
            bridge    (first (filter #(= :bridge (:bound-session %)) children))]
        (should= "hail-1" (:id parent))
        (should= 2 (count child-ids))
        (should= 2 (count (set child-ids)))
        (doseq [id child-ids]
          (should (short-uuid? id)))
        (should= "hail-1" (:source-hail bridge))
        (should= :atticus (:crew bridge))
        (should= :bridge (:bound-session bridge)))))

  (it "moves no-recipient hails to undeliverable on tick, enriched in place"
    (let [session-store (memory/create-store)]
      (fs/mkdirs (nexus/get :fs) "/test/isaac/hail/pending")
      (fs/spit (nexus/get :fs) "/test/isaac/hail/pending/hail-1.edn"
               (pr-str {:id "hail-1" :frequencies {:session-tags #{:role/command}} :from :cli}))
      (sut/tick! {:cfg           {:crew {:bartholomew {:tags #{:role/engineer}}}}
                  :session-store session-store})
      (should-not (fs/exists? (nexus/get :fs) "/test/isaac/hail/pending/hail-1.edn"))
      (should= {:id "hail-1" :frequencies {:session-tags #{:role/command}} :from :cli :reason :no-recipients}
               (read-string (fs/slurp (nexus/get :fs) "/test/isaac/hail/undeliverable/hail-1.edn")))))

  (it "requires a registered session store when tick omits :session-store"
    (nexus/-with-nested-nexus {:sessions {}}
      (fs/mkdirs (nexus/get :fs) "/test/isaac/hail/pending")
      (fs/spit (nexus/get :fs) "/test/isaac/hail/pending/hail-1.edn"
               (pr-str {:id "hail-1" :frequencies {:session [:engine-room]} :from :cli}))
      (let [error (try (sut/tick! {})
                       nil
                       (catch clojure.lang.ExceptionInfo e e))]
        (should-not-be-nil error)
        (should (str/includes? (ex-message error) ":sessions :store")))))

  (it "routes an explicit session id without band session-tag filtering"
    (let [result (sut/resolve-obligations test-cfg
                                          {"ci-failure" {:session-tags #{:orchestration} :reach :one}}
                                          [{:id "glimmering-cardinal" :crew "main" :tags #{}}]
                                          {:id          "hail-1"
                                           :frequencies {:band "ci-failure" :session ["glimmering-cardinal"]}})]
      (should= {:delivery {:id          "hail-1"
                           :frequencies {:band    "ci-failure"
                                         :session [:glimmering-cardinal]
                                         :reach   :one}
                           :crew        :main
                           :bound-session :glimmering-cardinal
                           :attempts    0}}
               result)))

  (it "does not fan out when the hail names an explicit session despite band reach :all"
    (let [result (sut/resolve-obligations test-cfg
                                          {"alert" {:session-tags #{:role/command} :reach :all}}
                                          [{:id "bridge" :crew "atticus" :tags #{:role/command}}
                                           {:id "first-watch" :crew "cordelia" :tags #{:role/command}}]
                                          {:id          "hail-1"
                                           :frequencies {:band "alert" :session ["bridge"]}})]
      (should= {:delivery {:id          "hail-1"
                           :frequencies {:band "alert" :session [:bridge] :reach :one}
                           :crew        :atticus
                           :bound-session :bridge
                           :attempts    0}}
               result)))

  (it "does not spawn when an explicit session is missing despite band create :if-missing"
    (let [result (sut/resolve-obligations test-cfg
                                          {"spawn-band" {:session-tags #{:wip}
                                                         :reach        :one
                                                         :create       :if-missing}}
                                          []
                                          {:id          "hail-1"
                                           :frequencies {:band "spawn-band" :session ["missing-room"]}})]
      (should= {:undeliverable {:id          "hail-1"
                                 :frequencies {:band "spawn-band" :session ["missing-room"]}
                                 :reason      :no-recipients}}
               result)))

  (it "applies band with-crew when the hail names an explicit session"
    (let [result (sut/resolve-obligations test-cfg
                                          {"gauge-check" {:with-crew "navigator"}}
                                          [{:id "engine-room" :crew "main"}]
                                          {:id          "hail-1"
                                           :frequencies {:band "gauge-check" :session ["engine-room"]}})]
      (should= :navigator (:crew (:delivery result)))))

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
