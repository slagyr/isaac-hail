(ns isaac.config.hail-loader-spec
  (:require
    [c3kit.apron.schema :as schema]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [isaac.config.validation]
    [speclj.core :refer :all]))

(defn- hail-manifest []
  (edn/read-string (slurp (io/resource "isaac-manifest.edn"))))

(defn- hail-band-schema []
  (get-in (hail-manifest) [:isaac.config/schema :hail :schema :value-spec]))

(describe "config hail schema"

  (it "conforms hail band declarations"
    (let [result (schema/conform (hail-band-schema)
                                 {:session-tags  [:project/chess]
                                  :reach         :one
                                  :create :if-missing})]
      (should-not (schema/error? result))
      (should= {:session-tags  [:project/chess]
                :reach         :one
                :create :if-missing}
               result)))

  (it "conforms hail prompts on band declarations"
    (let [result (schema/conform (hail-band-schema)
                                 {:session-tags [:bean/ready]
                                  :prompt       "Pick up the bean."
                                  :reach        :all})]
      (should-not (schema/error? result))
      (should= {:session-tags [:bean/ready]
                :prompt       "Pick up the bean."
                :reach        :all}
               result)))

  (it "conforms optional band :data maps"
    (let [result (schema/conform (hail-band-schema)
                                 {:session-tags [:bean/ready]
                                  :reach        :one
                                  :data         {:bean-repo "isaac"
                                                 :bean-id   "{{bean-id}}"}})]
      (should-not (schema/error? result))
      (should= {:session-tags [:bean/ready]
                :reach        :one
                :data         {:bean-repo "isaac"
                               :bean-id   "{{bean-id}}"}}
               result)))

  (it "rejects band :data that is not a map"
    (let [result (schema/conform (hail-band-schema)
                                 {:session-tags [:bean/ready]
                                  :reach        :one
                                  :data         "not-a-map"})]
      (should (schema/error? result))
      (should (.contains (pr-str (schema/message-map result)) "data"))))

  (it "rejects :crew as a seq"
    (let [result (schema/conform (hail-band-schema)
                                 {:crew         [:ops]
                                  :session-tags [:project/chess]
                                  :reach        :one})]
      (should (schema/error? result))
      (should (.contains (pr-str (schema/message-map result)) "crew"))))

  (it "conforms hail bands that inherit addressing via :base"
    (let [result (schema/conform (hail-band-schema)
                                 {:base "_isaac-template"
                                  :data {:plan-hail "isaac-plan"}})]
      (should-not (schema/error? result))
      (should= {:base "_isaac-template"
                :data {:plan-hail "isaac-plan"}}
               result)))

  (it "rejects hail bands without any addressing fields or :base"
    (let [result (schema/conform (hail-band-schema)
                                 {:reach :one})]
      (should (schema/error? result))
      (should= {:addressing "must include at least one of :session, :session-tags, :crew, :base"}
               (schema/message-map result)))))