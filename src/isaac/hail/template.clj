(ns isaac.hail.template
  (:require
    [isaac.foundation.template :as template]))

(defn render
  "Render band placeholders, replacing missing bindings with empty text."
  [text bindings]
  (when text
    (template/render text bindings {:on-missing :empty})))
