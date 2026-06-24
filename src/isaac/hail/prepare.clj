(ns isaac.hail.prepare
  (:require
    [clojure.string :as str]
    [isaac.hail.store :as store]
    [isaac.hail.template :as template]))

(defn- blank? [v]
  (or (nil? v) (and (string? v) (str/blank? v))))

(defn- band-name [record]
  (get-in record [:frequency :band]))

(defn- band-entry [cfg band-name]
  (when (and cfg band-name)
    (or (get-in cfg [:hail band-name])
        (get-in cfg [:hail (keyword band-name)]))))

(defn render-band-prompt
  "When a hail targets a band and has no explicit :prompt, render the band's
   companion template with :params."
  [record cfg]
  (if (or (not (blank? (:prompt record))) (blank? (band-name record)))
    record
    (if-let [template (:prompt (band-entry cfg (band-name record)))]
      (assoc record :prompt (template/render template (or (:params record) {})))
      record)))

(defn inherit-thread-id
  "When :reply-to is set and :thread-id is omitted, inherit from the parent hail."
  [record]
  (if (or (not (blank? (:thread-id record))) (blank? (:reply-to record)))
    record
    (if-let [parent (store/find-by-id (str (:reply-to record)))]
      (assoc record :thread-id (:thread-id parent))
      record)))

(defn default-thread-id
  "Default :thread-id to the hail's own :id when still unset."
  [record]
  (if (blank? (:thread-id record))
    (assoc record :thread-id (:id record))
    record))

(defn enrich
  "Apply threading and band-prompt rules before persisting a hail record."
  ([record] (enrich record nil))
  ([record cfg]
   (-> record
       inherit-thread-id
       (render-band-prompt cfg))))