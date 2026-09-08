(ns isaac.hail.comm
  (:require
    [isaac.comm.protocol :as comm]))

(def events (atom []))

(defn clear-events!
  "Test hook — drop recorded hail-comm events."
  []
  (reset! events []))

(deftype HailComm [])

(extend HailComm
  comm/Comm
  (merge comm/defaults
         {:on-exhausted (fn [_ _ _] :wrap-up)
          :on-bulletin
          (fn [_ session-key bulletin]
            (let [kind     (:kind bulletin)
                  kind-str (if (keyword? kind) (subs (str kind) 1) (str kind))
                  text     (or (:text bulletin) (:content bulletin))]
              (swap! events conj (cond-> {:event   "bulletin"
                                          :session session-key
                                          :kind    kind-str}
                                   text (assoc :text text)))))
          :send!        (fn [_ _] {:ok false :transient? false})}))

(def channel (->HailComm))
