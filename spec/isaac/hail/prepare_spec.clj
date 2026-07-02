(ns isaac.hail.prepare-spec
  (:require
    [isaac.fs :as fs]
    [isaac.hail.prepare :as sut]
    [isaac.hail.store :as store]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "hail.prepare"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (example)))

  (it "renders a band prompt from template and params when prompt is omitted"
    (let [cfg {:hail {"engineering-intercom" {:prompt "Hello {{name}}."}}}
          record {:frequencies {:band "engineering-intercom"} :params {:name "Marigold"}}]
      (should= "Hello Marigold."
               (:prompt (sut/render-band-prompt record cfg)))))

  (it "keeps an explicit prompt override"
    (let [cfg {:hail {"engineering-intercom" {:prompt "Hello {{name}}."}}}
          record {:frequencies {:band "engineering-intercom"}
                  :params    {:name "Marigold"}
                  :prompt    "Override."}]
      (should= "Override." (:prompt (sut/render-band-prompt record cfg)))))

  (it "inherits thread-id from the reply-to hail"
    (let [fs* (nexus/get :fs)]
      (fs/mkdirs fs* "/test/isaac/hail/pending")
      (fs/spit fs* "/test/isaac/hail/pending/hail-42.edn"
               (pr-str {:id "hail-42" :thread-id "thread-9"}))
      (should= "thread-9"
               (:thread-id (sut/inherit-thread-id {:reply-to "hail-42"})))))

  (it "defaults thread-id to the hail id"
    (should= "hail-1" (:thread-id (sut/default-thread-id {:id "hail-1"}))))

  (it "merges band data with params and interpolates string values"
    (let [cfg {:hail {"bean-pickup" {:data {:bean-repo "isaac"
                                            :bean-id   "{{bean-id}}"}}}}
          record {:frequencies {:band "bean-pickup"}
                  :params    {:bean-id "isaac-iz3a"}}]
      (should= {:bean-repo "isaac" :bean-id "isaac-iz3a"}
               (:data (sut/enrich-band-data record cfg)))))

  (it "lets params override same-named band data keys"
    (let [cfg {:hail {"bean-pickup" {:data {:bean-repo "isaac"
                                            :sector    "alpha"}}}}
          record {:frequencies {:band "bean-pickup"}
                  :params    {:sector "gamma"}}]
      (should= {:bean-repo "isaac" :sector "gamma"}
               (:data (sut/enrich-band-data record cfg)))))

  (it "omits :data when the band has no declared data"
    (let [cfg {:hail {"bean-pickup" {:prompt "Go."}}}
          record {:frequencies {:band "bean-pickup"}}]
      (should-not (contains? (sut/enrich-band-data record cfg) :data))))

  (it "omits :data when the band has no declared data even if params are present"
    (let [cfg {:hail {"bean-pickup" {:prompt "Go."}}}
          record {:frequencies {:band "bean-pickup"} :params {:n 1}}]
      (should-not (contains? (sut/enrich-band-data record cfg) :data)))))