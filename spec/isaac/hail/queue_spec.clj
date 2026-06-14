(ns isaac.hail.queue-spec
  (:require
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.hail.queue :as sut]
    [isaac.nexus :as nexus]
    [isaac.tool.memory :as memory]
    [speclj.core :refer :all]))

(describe "hail.queue"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (example)))

  (it "writes a hail record under hail/pending"
    (binding [memory/*now* (java.time.Instant/parse "2026-05-23T12:00:00Z")]
      (let [record (sut/send! {:frequency {:band "bean-pickup"}
                               :payload   {:n 1}
                               :from      :cli})]
        (should= {:id        "hail-1"
                  :frequency {:band "bean-pickup"}
                  :payload   {:n 1}
                  :from      :cli
                  :sent-at   "2026-05-23T12:00:00Z"}
                 record)
        (should= record
                 (sut/read-pending "hail-1")))))

  (it "mints sequential hail ids"
    (should= "hail-1" (:id (sut/send! {:frequency {:band "bean-pickup"} :from :cli})))
    (should= "hail-2" (:id (sut/send! {:frequency {:band "bean-pickup"} :from :cli}))))

  (it "ignores caller-supplied id and sent-at"
    (binding [memory/*now* (java.time.Instant/parse "2026-05-23T12:00:00Z")]
      (let [record (sut/send! {:id        "spoofed"
                               :sent-at   "1999-01-01T00:00:00Z"
                               :frequency {:band "bean-pickup"}
                               :from      :cli})]
        (should= "hail-1" (:id record))
        (should= "2026-05-23T12:00:00Z" (:sent-at record))
        (should= record (sut/read-pending "hail-1")))))

  (it "writes through a temp file before moving into pending"
    (let [ops*      (atom [])
          real-spit fs/spit
          real-move fs/move]
      (with-redefs [fs/spit (fn [fs* path content]
                              (swap! ops* conj [:spit path])
                              (real-spit fs* path content))
                    fs/move (fn [fs* source destination]
                              (swap! ops* conj [:move source destination])
                              (real-move fs* source destination))]
        (sut/send! {:frequency {:band "bean-pickup"} :from :cli}))
      (let [[spit-op move-op] (filter (fn [[op path]]
                                        (or (and (= :spit op) (str/includes? path "/hail/pending/"))
                                            (and (= :move op) (str/includes? path "/hail/pending/"))))
                                      @ops*)]
        (should= :spit (first spit-op))
        (should (not= "/test/isaac/hail/pending/hail-1.edn" (second spit-op)))
        (should (.endsWith ^String (second spit-op) ".tmp"))
        (should= [:move (second spit-op) "/test/isaac/hail/pending/hail-1.edn"] move-op)
        (should-not (fs/exists? (nexus/get :fs) (second spit-op))))))

  (it "stores the pending file at hail/pending/<id>.edn"
    (sut/send! {:frequency {:band "bean-pickup"} :from :cli})
    (should (fs/exists? (nexus/get :fs) "/test/isaac/hail/pending/hail-1.edn"))))
