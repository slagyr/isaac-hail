(ns isaac.hail.template-spec
  (:require
    [isaac.hail.template :as sut]
    [speclj.core :refer :all]))

(describe "hail.template"

  (it "renders {{var}} placeholders from bindings"
    (should= "Resonance climbing on primary, drift 0.03."
             (sut/render "Resonance climbing on {{coil}}, drift {{drift}}."
                         {:coil "primary" :drift 0.03})))

  (it "substitutes missing bindings with empty strings"
    (should= "Resonance climbing on , drift ."
             (sut/render "Resonance climbing on {{coil}}, drift {{drift}}." {}))))