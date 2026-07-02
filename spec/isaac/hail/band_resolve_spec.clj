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
    (should= {:crew "perceptor" :reach :all :session-tags [:isaac]}
             (sut/merge-bands {:crew "ops" :reach :one :session-tags [:isaac]}
                              {:crew "perceptor" :reach :all})))

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
    (let [raw {"_root"   {:session-tags [:isaac] :reach :one}
               "_mid"    {:base "_root" :data {:repo "isaac"}}
               "leaf"    {:base "_mid" :crew "perceptor"}}
          {:keys [bands errors]} (sut/resolve-slice raw)]
      (should= [] errors)
      (should= {:session-tags [:isaac]
                :reach        :one
                :data         {:repo "isaac"}
                :crew         "perceptor"}
               (get bands "leaf"))
      (should= nil (get bands "_root"))
      (should= nil (get bands "_mid"))))

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
    (let [raw {"_template" {:session-tags [:isaac] :reach :one}
               "isaac-work" {:base "_template" :crew "worker"}}
          {:keys [bands]} (sut/resolve-slice raw)]
      (should= nil (get bands "_template"))
      (should (contains? bands "isaac-work"))))

  (it "treats underscore-prefixed band names as templates"
    (should (sut/template-band? "_isaac-template"))
    (should-not (sut/template-band? "isaac-work"))))