(ns isaac.hail.delivery-feature-steps
  (:require
    [gherclj.core :as g :refer [defwhen helper!]]
    [isaac.config.api :as config]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.hail.delivery-worker :as delivery-worker]
    [isaac.nexus :as nexus]
    [isaac.session.store.spi :as store]))

(helper! isaac.hail.delivery-feature-steps)

(defn- mem-fs []
  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs)))

(defn- root-dir []
  (or (g/get :runtime-root-dir) (g/get :root)))

(defn hail-delivery-worker-ticks []
  (let [fs*           (mem-fs)
        root          (root-dir)
        cfg           (:config (loader/load-config-result {:root root :fs fs*}))
        session-store (store/create root)]
    (nexus/-with-nested-nexus {:config   (atom cfg)
                               :root     root
                               :fs       fs*
                               :sessions {:store session-store}}
      (config/dangerously-install-config! cfg "feature")
      (when-let [future* (first (delivery-worker/tick! {:cfg cfg :session-store session-store}))]
        (g/assoc! :turn-future future*)))))

(defwhen "the hail delivery worker ticks" isaac.hail.delivery-feature-steps/hail-delivery-worker-ticks)
