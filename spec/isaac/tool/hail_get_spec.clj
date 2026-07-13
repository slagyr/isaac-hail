(ns isaac.tool.hail-get-spec
  (:require
    [isaac.fs :as fs]
    [isaac.tool.hail-get :as sut]
    [isaac.nexus :as nexus]
    [isaac.session.store.memory :as memory-store]
    [isaac.session.store.spi :as session-store]
    [speclj.core :refer :all]))

(describe "hail-get tool"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (example)))

  (it "returns the hail record when found in failed/"
    (let [fs* (nexus/get :fs)]
      (fs/mkdirs fs* "/test/isaac/hail/failed")
      (fs/spit fs* "/test/isaac/hail/failed/hail-9.edn"
               (pr-str {:id "hail-9" :prompt "p" :thread-id "t" :reply-to "hail-1"})))
    (should= {:result {:id "hail-9" :prompt "p" :thread-id "t" :reply-to "hail-1" :lifecycle :failed}}
             (sut/hail-get-tool {"id" "hail-9"})))

  (it "returns :lifecycle :delivered for delivered/"
    (let [fs* (nexus/get :fs)]
      (fs/mkdirs fs* "/test/isaac/hail/delivered")
      (fs/spit fs* "/test/isaac/hail/delivered/hail-8.edn"
               (pr-str {:id "hail-8" :prompt "done"})))
    (should= :delivered (get-in (sut/hail-get-tool {"id" "hail-8"}) [:result :lifecycle])))

  (it "returns :lifecycle :in-flight from an active turn marker when deliveries/ file is gone"
    (let [fs* (nexus/get :fs)
          store (memory-store/create-store)]
      (session-store/register-store! store)
      (session-store/record-turn-marker! store "engine-room"
                                 {:source :hail
                                  :delivery-id "bc9f2710"
                                  :delivery {:id "bc9f2710" :prompt "work" :thread-id "bc9f2710"}}))
    (should= {:result {:id "bc9f2710" :prompt "work" :thread-id "bc9f2710" :lifecycle :in-flight}}
             (sut/hail-get-tool {"id" "bc9f2710"})))

  (it "returns an error when id is missing"
    (should= {:isError true :error "id is required"}
             (sut/hail-get-tool {})))

  (it "returns an error when hail is not found"
    (should= {:isError true :error "hail not found: missing"}
             (sut/hail-get-tool {"id" "missing"}))))