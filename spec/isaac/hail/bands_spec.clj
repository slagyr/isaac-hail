(ns isaac.hail.bands-spec
  (:require
    [isaac.foundation.reconfigurable :as reconfigurable]
    [isaac.hail.bands :as sut]
    [speclj.core :refer :all]))

(describe "hail bands"

  (it "looks up configured bands after startup"
    (let [registry (sut/make nil)]
      (reconfigurable/on-load registry {"bean.ready" {:crew "ops" :session-tags [:project/chess] }})
      (should= {:crew "ops" :session-tags [:project/chess]  :continuations 2}
               (sut/lookup registry "bean.ready"))))

  (it "returns all configured bands"
    (let [registry (sut/make nil)]
      (reconfigurable/on-load registry {"bean.ready" {:crew "ops" :session-tags [:project/chess] }})
      (should= {"bean.ready" {:crew "ops" :session-tags [:project/chess]  :continuations 2}}
               (sut/all-bands registry))))

  (it "replaces bands on config change"
    (let [registry (sut/make nil)]
      (reconfigurable/on-load registry {"bean.ready" {:crew "ops" :session-tags [:project/chess] }})
      (reconfigurable/on-config-change! registry {"bean.ready" {:crew "ops" :session-tags [:project/chess] }}
                                        {"verify.ready" {:session-tags [:bean/ready] }})
      (should= nil (sut/lookup registry "bean.ready"))
      (should= {:session-tags [:bean/ready]  :continuations 2}
               (sut/lookup registry "verify.ready"))))

  (it "defaults :continuations to 2 when the band omits it"
    (let [registry (sut/make nil)]
      (reconfigurable/on-load registry {"bean.ready" {:crew "ops" :session-tags [:project/chess] }})
      (should= 2 (:continuations (sut/lookup registry "bean.ready")))))

  (it "keeps an explicit :continuations budget"
    (let [registry (sut/make nil)]
      (reconfigurable/on-load registry {"engine-band" {:session-tags [:project/warp-coil] :continuations 1}})
      (should= 1 (:continuations (sut/lookup registry "engine-band")))))
  )