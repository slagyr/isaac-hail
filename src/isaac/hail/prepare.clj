(ns isaac.hail.prepare
  (:require
    [clojure.string :as str]
    [isaac.foundation.template :as foundation-template]
    [isaac.hail.band-resolve :as band-resolve]
    [isaac.hail.template :as template]))

(defn- blank? [v]
  (or (nil? v) (and (string? v) (str/blank? v))))

(defn- band-name [record]
  (get-in record [:frequencies :band]))

(defn- band-entry [cfg band-name]
  (when (and cfg band-name)
    (let [bands (band-resolve/resolved-slice (:hail cfg))]
      (or (get bands band-name)
          (get bands (keyword band-name))))))

(defn render-band-prompt
  "When a hail targets a band and has no explicit :prompt, render the band's
   companion template with :params."
  [record cfg]
  (if (or (not (blank? (:prompt record))) (blank? (band-name record)))
    record
    (if-let [template (:prompt (band-entry cfg (band-name record)))]
      (assoc record :prompt (template/render template (or (:params record) {})))
      record)))

(defn- effective-data [cfg record]
  (when-let [band-data (not-empty (:data (band-entry cfg (band-name record))))]
    (let [params (or (:params record) {})]
      (foundation-template/render-all (merge band-data params) params {:on-missing :empty}))))

(defn enrich-band-data
  "Merge band :data with per-hail :params (params win), interpolate {{var}}
   placeholders in string values, and persist the result on :data."
  [record cfg]
  (if-let [data (effective-data cfg record)]
    (assoc record :data data)
    (dissoc record :data)))

