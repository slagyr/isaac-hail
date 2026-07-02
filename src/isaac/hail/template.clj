(ns isaac.hail.template
  (:require
    [clojure.string :as str]))

(defn- binding-value [bindings k]
  (let [kw (if (keyword? k) k (keyword k))]
    (or (get bindings kw)
        (get bindings (name kw))
        (get bindings (str kw)))))

(defn render
  "Render Mustache-lite {{var}} placeholders. Missing bindings become \"\"."
  [template bindings]
  (when template
    (str/replace template #"\{\{([\w-]+)\}\}"
                 (fn [[_ k]]
                   (str (or (binding-value bindings k) ""))))))