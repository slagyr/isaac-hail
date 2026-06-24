(ns isaac.hail.store-spec
  (:require
    [isaac.fs :as fs]
    [isaac.hail.store :as sut]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(defn- write-hail! [subdir id record]
  (let [fs*  (nexus/get :fs)
        path (str "/test/isaac/hail/" subdir "/" id ".edn")]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit fs* path (pr-str record))))

(describe "hail.store"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (example)))

  (it "finds a hail record in any hail subdirectory"
    (write-hail! "delivered" "hail-7" {:id "hail-7" :prompt "done"})
    (should= {:id "hail-7" :prompt "done"} (sut/find-by-id "hail-7")))

  (it "returns nil when no matching id exists"
    (should-be-nil (sut/find-by-id "missing")))

  (it "reports the highest hail-N id across subdirectories"
    (write-hail! "delivered" "hail-1" {:id "hail-1"})
    (write-hail! "pending" "hail-3" {:id "hail-3"})
    (should= 3 (sut/max-hail-seq))))