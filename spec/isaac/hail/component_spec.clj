(ns isaac.hail.component-spec
  (:require
    [isaac.component.factory :as component-factory]
    [isaac.component.protocol :as component]
    [isaac.hail.component]
    [isaac.hail.delivery-worker :as delivery-worker]
    [isaac.hail.router :as router]
    [speclj.core :refer :all]))

(describe "hail runtime component"

  (it "starts and stops routing and delivery workers"
    (let [calls    (atom [])
          instance (component-factory/create :hail-runtime {})]
      (with-redefs [router/start!          #(do (swap! calls conj [:router/start %]) ::router)
                    delivery-worker/start! #(do (swap! calls conj [:delivery/start %]) ::delivery)
                    delivery-worker/stop!  #(swap! calls conj [:delivery/stop %])
                    router/stop!           #(swap! calls conj [:router/stop %])]
        (component/start instance)
        (component/stop instance))
      (should= [[:router/start {}]
                [:delivery/start {}]
                [:delivery/stop ::delivery]
                [:router/stop ::router]]
               @calls)))

  )
