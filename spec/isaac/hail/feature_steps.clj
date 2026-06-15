(ns isaac.hail.feature-steps
  "Hail feature harness hooks layered on foundation CLI steps."
  (:require
    [gherclj.core :as g :refer [helper!]]
    [isaac.foundation.cli-steps :as fcli]
    [isaac.tool.memory :as memory]))

(helper! isaac.hail.feature-steps)

(fcli/register-isaac-run-wrapper!
  (fn [thunk]
    (if-let [ct (g/get :current-time)]
      (binding [memory/*now* ct] (thunk))
      (thunk))))