(ns isaac.hail.bands-spec
  (:require
    [isaac.reconfigurable :as reconfigurable]
    [isaac.hail.bands :as sut]
    [speclj.core :refer :all]))

(describe "hail bands"

  (it "looks up configured bands after startup"
    (let [registry (sut/make nil)]
      (reconfigurable/on-load registry {"bean.ready" {:crew-tags [:role/worker]}})
      (should= {:crew-tags [:role/worker] :reach :one}
               (sut/lookup registry "bean.ready"))))

  (it "returns all configured bands"
    (let [registry (sut/make nil)]
      (reconfigurable/on-load registry {"bean.ready" {:crew-tags [:role/worker]}})
      (should= {"bean.ready" {:crew-tags [:role/worker] :reach :one}}
               (sut/all-bands registry))))

  (it "replaces bands on config change"
    (let [registry (sut/make nil)]
      (reconfigurable/on-load registry {"bean.ready" {:crew-tags [:role/worker]}})
      (reconfigurable/on-config-change! registry {"bean.ready" {:crew-tags [:role/worker]}}
                                        {"verify.ready" {:crew-tags [:role/verify] :reach :all}})
      (should= nil (sut/lookup registry "bean.ready"))
      (should= {:crew-tags [:role/verify] :reach :all}
               (sut/lookup registry "verify.ready")))))