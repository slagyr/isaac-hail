(ns isaac.hail.router-spec
  (:require
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.hail.router :as sut]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]
    [isaac.session.store.spi :as store]
    [isaac.session.store.memory :as memory]
    [speclj.core :refer :all]))

(def ^:private short-uuid-re #"^[0-9a-f]{8}$")

(defn- short-uuid? [s]
  (and (string? s) (re-matches short-uuid-re s)))

(def ^:private test-cfg
  {:defaults {:crew "main"}})

(describe "hail router"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (example)))

  (it "binds a reach-one band to the only matching session, enriching the hail in place"
    (let [result (sut/resolve-obligations test-cfg
                                          {"engineering-intercom" {:session-tags #{:role/engineer} :reach :one}}
                                          [{:id "engine-room" :crew "bartholomew" :tags #{:role/engineer}}]
                                          {:id "hail-1" :frequency {:band "engineering-intercom"}})]
      (should= {:delivery {:id       "hail-1"
                           :frequency {:band "engineering-intercom"}
                           :crew     :bartholomew
                           :session  :engine-room
                           :attempts 0}}
               result)))

  (it "leaves a reach-one pool unbound with sorted candidates"
    (let [result (sut/resolve-obligations test-cfg
                                          {}
                                          [{:id "first-watch" :crew "cordelia" :tags #{:role/command}}
                                           {:id "bridge" :crew "atticus" :tags #{:role/command}}]
                                          {:id        "hail-1"
                                           :frequency {:session-tags #{:role/command}}
                                           :reach     :one})]
      (should= {:delivery {:id         "hail-1"
                           :frequency  {:session-tags #{:role/command}}
                           :reach      :one
                           :crew       nil
                           :session    nil
                           :candidates [{:crew :atticus :session :bridge}
                                        {:crew :cordelia :session :first-watch}]
                           :attempts   0}}
               result)))

  (it "selects sessions whose :crew matches a frequency :crew selector"
    (let [result (sut/resolve-obligations test-cfg
                                          {}
                                          [{:id "agile-voyage" :crew "main"}
                                           {:id "side-job" :crew "marvin"}]
                                          {:id        "hail-1"
                                           :frequency {:crew "main"}
                                           :reach     :one
                                           :prompt    "Work"})]
      (should= {:delivery {:id        "hail-1"
                           :frequency {:crew "main"}
                           :reach     :one
                           :prompt    "Work"
                           :crew      :main
                           :session   :agile-voyage
                           :attempts  0}}
               result)))

  (it "returns no-recipients when no session selector is present"
    (let [result (sut/resolve-obligations test-cfg
                                          {}
                                          [{:id "engine-room" :crew "bartholomew"}]
                                          {:id "hail-1" :frequency {:reach :one}})]
      (should= {:undeliverable {:id "hail-1" :frequency {:reach :one} :reason :no-recipients}}
               result)))

  (it "emits a spawn delivery with resolved crew when no session matches"
    (let [hail   {:id "hail-1"
                  :frequency {:session-tags #{:project/warp-coil}
                              :reach :one
                              :spawn-session true}}
          result (sut/resolve-obligations test-cfg {} [] hail)]
      (should= {:delivery (assoc hail :crew :main :session nil :attempts 0)}
               result)))

  (it "returns unknown-band when the referenced band is missing"
    (let [result (sut/resolve-obligations test-cfg
                                          {}
                                          []
                                          {:id "hail-1" :frequency {:band "phantom-band"}})]
      (should= {:undeliverable {:id "hail-1" :frequency {:band "phantom-band"} :reason :unknown-band}}
               result)))

  (it "fans reach-all into a broadcast parent plus per-session children in session order"
    (let [result (sut/resolve-obligations test-cfg
                                          {}
                                          [{:id "first-watch" :crew "cordelia" :tags #{:role/command}}
                                           {:id "bridge" :crew "atticus" :tags #{:role/command}}]
                                          {:id        "hail-1"
                                           :frequency {:session-tags #{:role/command}}
                                           :reach     :all})]
      (should= {:broadcast {:parent   {:id "hail-1" :frequency {:session-tags #{:role/command}} :reach :all}
                            :children [{:crew :atticus :session :bridge}
                                       {:crew :cordelia :session :first-watch}]}}
               result)))

  (it "writes a flat delivery keeping the hail id and removes the pending hail on tick"
    (let [session-store (memory/create-store)]
      (store/open-session! session-store "engine-room" {:crew "bartholomew"})
      (fs/mkdirs (nexus/get :fs) "/test/isaac/hail/pending")
      (fs/spit (nexus/get :fs) "/test/isaac/hail/pending/hail-1.edn"
               (pr-str {:id "hail-1" :frequency {:session [:engine-room]} :from :cli}))
      (sut/tick! {:cfg           {:defaults {:crew "main"}
                                  :crew {:bartholomew {:model "grover"}}}
                  :session-store session-store})
      (should-not (fs/exists? (nexus/get :fs) "/test/isaac/hail/pending/hail-1.edn"))
      (should= {:id       "hail-1"
                :frequency {:session [:engine-room]}
                :from     :cli
                :crew     :bartholomew
                :session  :engine-room
                :attempts 0}
               (read-string (fs/slurp (nexus/get :fs) "/test/isaac/hail/deliveries/hail-1.edn")))))

  (it "writes a broadcast parent plus child delivery hails on tick for reach :all"
    (let [session-store (memory/create-store)]
      (store/open-session! session-store "bridge" {:crew "atticus" :tags #{:role/command}})
      (store/open-session! session-store "first-watch" {:crew "cordelia" :tags #{:role/command}})
      (fs/mkdirs (nexus/get :fs) "/test/isaac/hail/pending")
      (fs/spit (nexus/get :fs) "/test/isaac/hail/pending/hail-1.edn"
               (pr-str {:id "hail-1" :frequency {:session-tags #{:role/command}} :reach :all :from :cli}))
      (sut/tick! {:cfg           {:crew {:atticus {:tags #{:role/command}}
                                         :cordelia {:tags #{:role/command}}}}
                  :session-store session-store})
      (should-not (fs/exists? (nexus/get :fs) "/test/isaac/hail/pending/hail-1.edn"))
      (let [parent    (read-string (fs/slurp (nexus/get :fs) "/test/isaac/hail/broadcasts/hail-1.edn"))
            child-ids (:children parent)
            children  (mapv #(read-string (fs/slurp (nexus/get :fs)
                                                      (str "/test/isaac/hail/deliveries/" % ".edn")))
                            child-ids)
            bridge    (first (filter #(= :bridge (:session %)) children))]
        (should= "hail-1" (:id parent))
        (should= 2 (count child-ids))
        (should= 2 (count (set child-ids)))
        (doseq [id child-ids]
          (should (short-uuid? id)))
        (should= "hail-1" (:source-hail bridge))
        (should= :atticus (:crew bridge))
        (should= :bridge (:session bridge)))))

  (it "moves no-recipient hails to undeliverable on tick, enriched in place"
    (let [session-store (memory/create-store)]
      (fs/mkdirs (nexus/get :fs) "/test/isaac/hail/pending")
      (fs/spit (nexus/get :fs) "/test/isaac/hail/pending/hail-1.edn"
               (pr-str {:id "hail-1" :frequency {:session-tags #{:role/command}} :from :cli}))
      (sut/tick! {:cfg           {:crew {:bartholomew {:tags #{:role/engineer}}}}
                  :session-store session-store})
      (should-not (fs/exists? (nexus/get :fs) "/test/isaac/hail/pending/hail-1.edn"))
      (should= {:id "hail-1" :frequency {:session-tags #{:role/command}} :from :cli :reason :no-recipients}
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