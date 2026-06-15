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
                                 {:crew-tags [:role/worker]
                                  :reach     :one})]
      (should-not (schema/error? result))
      (should= {:crew-tags [:role/worker]
                :reach     :one}
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

  (it "rejects hail bands without any addressing fields"
    (let [result (schema/conform (hail-band-schema)
                                 {:reach :one})]
      (should (schema/error? result))
      (should= {:addressing "must include at least one of :crew, :crew-tags, :session, :session-tags"}
               (schema/message-map result)))))
