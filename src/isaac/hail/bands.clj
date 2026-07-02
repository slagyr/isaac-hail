(ns isaac.hail.bands
  (:require
    [isaac.hail.band-resolve :as band-resolve]
    [isaac.reconfigurable :as reconfigurable]))

(defprotocol BandRegistry
  (lookup [this band-name])
  (all-bands [this]))

(defn- with-band-defaults [band]
  (cond-> band
    (and band (nil? (:reach band))) (assoc :reach :one)))

(defn- load-slice [slice]
  (into {} (map (fn [[band-name band]] [band-name (with-band-defaults band)]))
        (band-resolve/resolved-slice (or slice {}))))

(deftype HailBands [bands*]
  BandRegistry
  (lookup [_ band-name]
    (get @bands* band-name))
  (all-bands [_]
    @bands*)

  reconfigurable/Reconfigurable
  (on-load [_ slice]
    (reset! bands* (load-slice slice)))
  (on-config-change! [_ _old-slice new-slice]
    (reset! bands* (load-slice new-slice)))
  (on-unload [_ _slice]
    (reset! bands* {})))

(defn make [_host]
  (->HailBands (atom {})))

(def registry
  {:kind    :component
   :path    [:hail]
   :impl    "hail-bands"
   :factory make})
