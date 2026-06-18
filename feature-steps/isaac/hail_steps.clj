(ns isaac.hail-steps
  "Hail-specific gherclj steps ported from the monolith server-steps hail
   section, plus clock binding for deterministic CLI timestamps."
  (:require
    [gherclj.core :as g :refer [defwhen helper!]]
    [isaac.agent.config.runtime :as agent-runtime]
    [isaac.config.api :as config]
    [isaac.config.loader :as loader]
    [isaac.foundation.cli-steps :as fcli]
    [isaac.fs :as fs]
    [isaac.hail.delivery-worker :as hail-delivery-worker]
    [isaac.hail.router :as hail-router]
    [isaac.logger :as log]
    [isaac.module.loader :as module-loader]
    [isaac.nexus :as nexus]
    [isaac.server.server-steps :as server-steps]
    [isaac.session.store.spi :as store]
    [isaac.tool.memory :as memory]))

(helper! isaac.hail-steps)

(fcli/register-isaac-run-wrapper!
  (fn [thunk]
    (if-let [ct (g/get :current-time)]
      (binding [memory/*now* ct] (thunk))
      (thunk))))

(defn- server-fs []
  (or (g/get :mem-fs) (fs/real-fs)))

(defn- with-server-fs [f]
  (let [fs* (server-fs)]
    (nexus/-with-nested-nexus {:fs fs*}
      (f))))

(defn- runtime-root-dir []
  (or (g/get :runtime-root-dir) (g/get :root)))

(defn- deep-merge [a b]
  (if (and (map? a) (map? b))
    (merge-with deep-merge a b)
    (or b a)))

(defn- load-server-config [root fs*]
  (let [load!       #(:config (loader/load-config-result {:root root :fs fs*}))
        entity-dir? #(with-server-fs
                       (fn []
                         (seq (fs/children fs* (str root "/config/" %)))))
        cfg         (load!)]
    (if (and (or (entity-dir? "crew") (entity-dir? "models") (entity-dir? "providers"))
             (empty? (or (:crew cfg) {}))
             (empty? (or (:models cfg) {}))
             (empty? (or (:providers cfg) {})))
      (load!)
      cfg)))

(defn- current-server-config []
  (let [home     (g/get :root)
        fs*      (server-fs)
        base     (with-server-fs #(load-server-config home fs*))
        merged   (deep-merge base
                             (merge (or (g/get :server-config) {})
                                    (when-let [providers (g/get :provider-configs)]
                                      {:providers providers})))
        runtime  (runtime-root-dir)
        disc     (nexus/-with-nested-nexus {:fs fs*}
                   (module-loader/discover! merged {:root runtime
                                                    :cwd  (System/getProperty "user.dir")}))]
    (assoc merged
           :module-index (:index disc)
           :root runtime)))

(defn- record-turn-future! [futures]
  (when-let [future* (first futures)]
    (g/assoc! :turn-future future*)))

(defn- feature-session-store [root cfg]
  (or (store/registered-store)
      (do (agent-runtime/install! {:config cfg})
          (store/registered-store))))

(defn hail-router-ticks []
  (g/assoc! :runtime-root-dir (g/get :root))
  (with-server-fs
    (fn []
      (let [fs*           (server-fs)
            cfg           (current-server-config)
            root          (runtime-root-dir)
            session-store (feature-session-store root cfg)]
        (nexus/-with-nexus {:config   (atom cfg)
                            :root     root
                            :fs       fs*
                            :sessions {:store session-store}}
          (config/dangerously-install-config! cfg "spec")
          (hail-router/tick! {:cfg cfg :session-store session-store}))))))

(defn hail-delivery-worker-ticks []
  (log/set-output! :memory)
  (log/clear-entries!)
  (g/assoc! :runtime-root-dir (g/get :root))
  (with-server-fs
    (fn []
      (let [fs*           (server-fs)
            cfg           (current-server-config)
            root          (runtime-root-dir)
            session-store (feature-session-store root cfg)]
        (nexus/-with-nexus {:config   (atom cfg)
                            :root     root
                            :fs       fs*
                            :sessions {:store session-store}}
          (config/dangerously-install-config! cfg "spec")
          (record-turn-future! (hail-delivery-worker/tick! {:cfg cfg :session-store session-store})))))))

(defn hail-delivery-worker-ticks-at [iso]
  (log/set-output! :memory)
  (log/clear-entries!)
  (g/assoc! :runtime-root-dir (g/get :root))
  (with-server-fs
    (fn []
      (let [fs*           (server-fs)
            cfg           (current-server-config)
            root          (runtime-root-dir)
            session-store (feature-session-store root cfg)]
        (nexus/-with-nexus {:config   (atom cfg)
                            :root     root
                            :fs       fs*
                            :sessions {:store session-store}}
          (config/dangerously-install-config! cfg "spec")
          (record-turn-future! (hail-delivery-worker/tick! {:cfg           cfg
                                                            :now           (java.time.Instant/parse iso)
                                                            :session-store session-store})))))))

(defn isaac-system-started []
  (server-steps/server-running))

(defwhen "the hail router ticks" isaac.hail-steps/hail-router-ticks)

(defwhen "the hail delivery worker ticks" isaac.hail-steps/hail-delivery-worker-ticks)

(defwhen #"the hail delivery worker ticks at \"([^\"]+)\"" isaac.hail-steps/hail-delivery-worker-ticks-at)

(defwhen "the Isaac system is started" isaac.hail-steps/isaac-system-started)