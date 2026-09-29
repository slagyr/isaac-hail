(ns isaac.hail.band-resolve-spec
  (:require
    [isaac.hail.band-resolve :as sut]
    [speclj.core :refer :all]))

(describe "hail.band-resolve"

  (it "merges map-valued keys key-wise with child winning and base-only keys surviving"
    (should= {:data {:bean-repo "isaac"
                     :notification-comm "longwave"
                     :plan-hail "isaac-plan"}}
             (sut/merge-bands {:data {:bean-repo "isaac"
                                     :notification-comm "longwave"}}
                              {:data {:plan-hail "isaac-plan"}})))

  (it "replaces scalar keys wholesale without merge errors"
    (should= {:crew "perceptor" :session-tags [:isaac]}
             (sut/merge-bands {:crew "ops" :session-tags [:isaac]}
                              {:crew "perceptor"})))

  (it "inherits base prompt when child has no prompt"
    (let [{:keys [bands]} (sut/resolve-slice
                            {"_template" {:session-tags [:isaac] :prompt "Base body"}
                             "child"     {:base "_template" :crew "perceptor"}})
          resolved (get bands "child")]
      (should= "Base body" (:prompt resolved))
      (should= [:isaac] (:session-tags resolved))
      (should= "perceptor" (:crew resolved))
      (should-not (contains? resolved :base))))

  (it "keeps child prompt over base prompt"
    (let [{:keys [bands]} (sut/resolve-slice
                            {"template" {:session-tags [:isaac] :prompt "Base body"}
                             "child"    {:base "template" :crew "ops" :prompt "Child body"}})
          resolved (get bands "child")]
      (should= "Child body" (:prompt resolved))))

  (it "resolves transitive base chains"
    (let [raw {"_root"   {:session-tags [:isaac] }
               "_mid"    {:base "_root" :data {:repo "isaac"}}
               "leaf"    {:base "_mid" :crew "perceptor"}}
          {:keys [bands errors]} (sut/resolve-slice raw)]
      (should= [] errors)
      (should= {:session-tags [:isaac]
                
                :data         {:repo "isaac"}
                :crew         "perceptor"}
               (get bands "leaf"))
      (should= nil (get bands "_root"))
      (should= nil (get bands "_mid"))))

  (it "rejects reach in a band"
    (should (some #(re-find #"reach" (:value %))
                  (:errors (sut/resolve-slice {"work" {:crew "ops" :reach :one}})))))

  (it "reports a cycle in the base chain"
    (let [raw {"a" {:base "b" :crew "ops"}
               "b" {:base "a" :session-tags [:isaac]}}
          {:keys [bands errors]} (sut/resolve-slice raw)]
      (should (empty? bands))
      (should (some #(and (= "hail.a" (:key %))
                          (.contains (:value %) "cycle"))
                    errors))))

  (it "reports a missing base reference"
    (let [raw {"child" {:base "missing" :crew "ops"}}
          {:keys [bands errors]} (sut/resolve-slice raw)]
      (should (empty? bands))
      (should (some #(and (= "hail.child" (:key %))
                          (.contains (:value %) "missing"))
                    errors))))

  (it "detects cycles between template bands"
    (let [raw {"_alpha" {:base "_beta"}
               "_beta"  {:base "_alpha"}}
          {:keys [bands errors]} (sut/resolve-slice raw)]
      (should (empty? bands))
      (should (some #(and (.contains (:value %) "cycle") %) errors))))

  (it "excludes underscore-prefixed template bands from the resolved slice"
    (let [raw {"_template" {:session-tags [:isaac] }
               "isaac-work" {:base "_template" :crew "worker"}}
          {:keys [bands]} (sut/resolve-slice raw)]
      (should= nil (get bands "_template"))
      (should (contains? bands "isaac-work"))))

  (it "treats underscore-prefixed band names as templates"
    (should (sut/template-band? "_isaac-template"))
    (should-not (sut/template-band? "isaac-work")))

  (it "resolved-slice exposes addressable merged bands from a raw slice"
    (let [raw {"_engineering-template" {:data {:bean-repo "git@x:a/b.git"}}
               "engineering-work"      {:base "_engineering-template"
                                        :data {:notification-channel "engine"}}}]
      (should= {:data {:bean-repo "git@x:a/b.git" :notification-channel "engine"}}
               (get (sut/resolved-slice raw) "engineering-work")))))
(def ^:private hail-band-schema
  {:name :hail-band
   :type :map
   :schema
   {:crew         {:type :string}
    :create       {:type :keyword :validations [[:one-of? :never :if-missing]]}
    :session-tags {:type :seq :spec {:type :keyword}}
    :base         {:type :string}}})

(def ^:private root-schema
  {:schema {:hail {:type :map :key-spec {:type :string} :value-spec hail-band-schema}}})

(describe "check-config"
  (it "resolves from the conformed band, ignoring differing raw values (isaac-cgzd)"
    ;; Frontmatter's raw create: :never arrives as the string ":never"; config
    ;; conforms it to the keyword :never. The raw slice must never win.
    (let [result (sut/check-config {:config           {:hail {"ci-watch" {:crew "ops" :create :never :session-tags [:ci]}}}
                                     :result           {:raw {:hail {"ci-watch" {:crew "ops" :create ":never" :session-tags [":ci"]}}}}
                                     :effective-schema root-schema})]
      (should= [] (:errors result))))

  (it "has no errors when raw is empty and the conformed band is valid"
    (let [result (sut/check-config {:config           {:hail {"ci-watch" {:crew "ops" :create :never :session-tags [:ci]}}}
                                     :result           {:raw {:hail {}}}
                                     :effective-schema root-schema})]
      (should= [] (:errors result))))

  (it "detects a removed :reach key from the raw slice even though conform dropped it"
    (let [result (sut/check-config {:config           {:hail {"bogus" {:session-tags [:isaac]}}}
                                     :result           {:raw {:hail {"bogus" {:session-tags [:isaac] :reach ":one"}}}}
                                     :effective-schema root-schema})]
      (should (some #(re-find #"reach" (:value %)) (:errors result)))))

  (it "is empty when both conformed and raw hail slices are empty"
    (should= {:errors [] :warnings []}
             (sut/check-config {:config {} :result {:raw {}} :effective-schema root-schema}))))

(describe "apply-to-load-result!"
  (it "loads the conformed band value, not the raw un-coerced one (isaac-cgzd)"
    (let [result (sut/apply-to-load-result!
                   root-schema
                   {:config {:hail {"ci-watch" {:crew "ops" :create :never :session-tags [:ci]}}}
                    :raw    {:hail {"ci-watch" {:crew "ops" :create ":never" :session-tags [":ci"]}}}
                    :errors []})]
      (should= {"ci-watch" {:crew "ops" :create :never :session-tags [:ci]}}
               (get-in result [:config :hail]))
      (should= [] (:errors result))))

  (it "still reports a removed :reach key detected from the raw slice"
    (let [result (sut/apply-to-load-result!
                   root-schema
                   {:config {:hail {"bogus" {:session-tags [:isaac]}}}
                    :raw    {:hail {"bogus" {:session-tags [:isaac] :reach ":one"}}}
                    :errors []})]
      (should (some #(re-find #"reach" (:value %)) (:errors result)))))

  (it "passes the result through unchanged when both hail slices are empty"
    (let [result {:config {} :raw {} :errors []}]
      (should= result (sut/apply-to-load-result! root-schema result)))))
