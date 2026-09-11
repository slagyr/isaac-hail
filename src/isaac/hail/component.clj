(ns isaac.hail.component
  (:require
    [isaac.component.factory :as component-factory]
    [isaac.component.protocol :as component]
    [isaac.hail.delivery-worker :as delivery-worker]
    [isaac.hail.router :as router]))

(deftype HailRuntime [running*]
  component/Component
  (start [this]
    (reset! running* {:router   (router/start! {})
                      :delivery (delivery-worker/start! {})})
    this)
  (stop [this]
    (when-let [{:keys [delivery router]} @running*]
      (delivery-worker/stop! delivery)
      (router/stop! router))
    (reset! running* nil)
    this))

(defmethod component-factory/create :hail-runtime [_ _ctx]
  (->HailRuntime (atom nil)))
