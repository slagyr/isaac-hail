(ns isaac.tool.hail-get
  (:require
    [clojure.string :as str]
    [isaac.hail.store :as store]
    [isaac.tool.fs-bounds :as bounds]))

(defn hail-get-tool
  "Fetch a hail record by id from any hail subdirectory."
  [arguments]
  (let [args (bounds/string-key-map arguments)
        id   (some-> (get args "id") str str/trim)]
    (cond
      (str/blank? id)
      {:isError true :error "id is required"}

      :else
      (if-let [record (store/find-by-id id)]
        {:result record}
        {:isError true :error (str "hail not found: " id)}))))

(defn hail-get-tool-factory [_]
  {:description "Fetch a hail record by id (scans hail/ subdirectories)."
   :parameters  {:type       "object"
                 :properties {"id" {:type "string" :description "Hail id to fetch"}}
                 :required   ["id"]}
   :handler     #'hail-get-tool})