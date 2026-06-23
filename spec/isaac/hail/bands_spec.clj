(ns isaac.hail.bands-spec
  (:require
    [isaac.reconfigurable :as reconfigurable]
    [isaac.hail.bands :as sut]
    [speclj.core :refer :all]))

(describe "hail bands"

  (it "looks up configured bands after startup"
    (let [registry (sut/make nil)]
      (reconfigurable/on-load registry {"bean.ready" {:crew "ops" :session-tags [:project/chess] :reach :one}})
      (should= {:crew "ops" :session-tags [:project/chess] :reach :one}
               (sut/lookup registry "bean.ready"))))

  (it "returns all configured bands"
    (let [registry (sut/make nil)]
      (reconfigurable/on-load registry {"bean.ready" {:crew "ops" :session-tags [:project/chess] :reach :one}})
      (should= {"bean.ready" {:crew "ops" :session-tags [:project/chess] :reach :one}}
               (sut/all-bands registry))))

  (it "replaces bands on config change"
    (let [registry (sut/make nil)]
      (reconfigurable/on-load registry {"bean.ready" {:crew "ops" :session-tags [:project/chess] :reach :one}})
      (reconfigurable/on-config-change! registry {"bean.ready" {:crew "ops" :session-tags [:project/chess] :reach :one}}
                                        {"verify.ready" {:session-tags [:bean/ready] :reach :all}})
      (should= nil (sut/lookup registry "bean.ready"))
      (should= {:session-tags [:bean/ready] :reach :all}
               (sut/lookup registry "verify.ready")))))