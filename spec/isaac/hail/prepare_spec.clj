(ns isaac.hail.prepare-spec
  (:require
    [isaac.fs :as fs]
    [isaac.hail.prepare :as sut]
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
      (should-not (contains? (sut/enrich-band-data record cfg) :data))))

  (it "resolves inherited band data from an unresolved raw hail slice"
    (let [cfg {:hail {"_engineering-template" {:data {:bean-repo "git@x:a/b.git"
                                                      :notification-channel "shipwide"}}
                      "engineering-work"        {:base "_engineering-template"
                                                 :data {:notification-channel "engine"}}}}
          record {:frequencies {:band "engineering-work"}}]
      (should= {:notification-channel "engine" :bean-repo "git@x:a/b.git"}
               (:data (sut/enrich-band-data record cfg)))))

  (it "inherits a base prompt when the child band has no body"
    (let [cfg {:hail {"_engineering-template" {:prompt "Attend to {{task}} in the engine room."}
                      "engineering-work"        {:base "_engineering-template"}}}
          record {:frequencies {:band "engineering-work"}
                  :params    {:task "the coil"}}]
      (should= "Attend to the coil in the engine room."
               (:prompt (sut/render-band-prompt record cfg)))))

  (it "resolves transitive inherited data from an unresolved raw hail slice"
    (let [cfg {:hail {"_fleet-template"       {:data {:fleet "seventh" :deck "one"}}
                      "_engineering-template" {:base "_fleet-template"
                                             :data {:deck "engineering"}}
                      "engineering-work"      {:base "_engineering-template"}}}
          record {:frequencies {:band "engineering-work"}}]
      (should= {:fleet "seventh" :deck "engineering"}
               (:data (sut/enrich-band-data record cfg))))))