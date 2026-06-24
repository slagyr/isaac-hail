(ns isaac.tool.hail-get-spec
  (:require
    [isaac.fs :as fs]
    [isaac.tool.hail-get :as sut]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "hail-get tool"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (example)))

  (it "returns the hail record when found"
    (let [fs* (nexus/get :fs)]
      (fs/mkdirs fs* "/test/isaac/hail/failed")
      (fs/spit fs* "/test/isaac/hail/failed/hail-9.edn"
               (pr-str {:id "hail-9" :prompt "p" :thread-id "t" :reply-to "hail-1"})))
    (should= {:result {:id "hail-9" :prompt "p" :thread-id "t" :reply-to "hail-1"}}
             (sut/hail-get-tool {"id" "hail-9"})))

  (it "returns an error when id is missing"
    (should= {:isError true :error "id is required"}
             (sut/hail-get-tool {})))

  (it "returns an error when hail is not found"
    (should= {:isError true :error "hail not found: missing"}
             (sut/hail-get-tool {"id" "missing"}))))