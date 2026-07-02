(ns isaac.hail.queue-spec
  (:require
    [clojure.string :as str]
    [isaac.config.api :as config]
    [isaac.config.root :as root]
    [isaac.fs :as fs]
    [isaac.hail.queue :as sut]
    [isaac.nexus :as nexus]
    [isaac.tool.memory :as memory]
    [speclj.core :refer :all]))

(def ^:private short-uuid-re #"^[0-9a-f]{8}$")
(def ^:private uuid-re #"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

(defn- short-uuid? [s]
  (and (string? s) (re-matches short-uuid-re s)))

(defn- full-uuid? [s]
  (and (string? s) (re-matches uuid-re s)))

(describe "hail.queue"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (config/dangerously-install-config! nil "spec reset")
      (example)))

  (it "writes a hail record under hail/pending"
    (binding [memory/*now* (java.time.Instant/parse "2026-05-23T12:00:00Z")]
      (let [record (sut/send! {:frequencies {:band "bean-pickup"}
                               :params      {:n 1}
                               :from        :cli})
            id     (:id record)]
        (should (short-uuid? id))
        (should= id (:thread-id record))
        (should= {:id          id
                  :thread-id   id
                  :frequencies {:band "bean-pickup"}
                  :params      {:n 1}
                  :data        {:n 1}
                  :from        :cli
                  :sent-at     "2026-05-23T12:00:00Z"}
                 record)
        (should= record (sut/read-pending id)))))

  (it "mints a unique short-uuid each call"
    (let [id1 (:id (sut/send! {:frequencies {:band "bean-pickup"} :from :cli}))
          id2 (:id (sut/send! {:frequencies {:band "bean-pickup"} :from :cli}))]
      (should (short-uuid? id1))
      (should (short-uuid? id2))
      (should-not= id1 id2)))

  (it "ignores caller-supplied id and sent-at"
    (binding [memory/*now* (java.time.Instant/parse "2026-05-23T12:00:00Z")]
      (let [record (sut/send! {:id          "spoofed"
                               :sent-at     "1999-01-01T00:00:00Z"
                               :frequencies {:band "bean-pickup"}
                               :from        :cli})
            id     (:id record)]
        (should (short-uuid? id))
        (should= "2026-05-23T12:00:00Z" (:sent-at record))
        (should= record (sut/read-pending id)))))

  (it "writes through a temp file before moving into pending"
    (let [ops*      (atom [])
          real-spit fs/spit
          real-move fs/move
          record    (with-redefs [fs/spit (fn [fs* path content]
                                            (swap! ops* conj [:spit path])
                                            (real-spit fs* path content))
                                  fs/move (fn [fs* source destination]
                                            (swap! ops* conj [:move source destination])
                                            (real-move fs* source destination))]
                      (sut/send! {:frequencies {:band "bean-pickup"} :from :cli}))
          id        (:id record)]
      (let [[spit-op move-op] (filter (fn [[op path]]
                                        (or (and (= :spit op) (str/includes? path "/hail/pending/"))
                                            (and (= :move op) (str/includes? path "/hail/pending/"))))
                                      @ops*)]
        (should= :spit (first spit-op))
        (should (not= (str "/test/isaac/hail/pending/" id ".edn") (second spit-op)))
        (should (.endsWith ^String (second spit-op) ".tmp"))
        (should= [:move (second spit-op) (str "/test/isaac/hail/pending/" id ".edn")] move-op)
        (should-not (fs/exists? (nexus/get :fs) (second spit-op))))))

  (it "stores the pending file at hail/pending/<id>.edn"
    (let [id (:id (sut/send! {:frequencies {:band "bean-pickup"} :from :cli}))]
      (should (fs/exists? (nexus/get :fs) (str "/test/isaac/hail/pending/" id ".edn")))))

  (it "does not read or write hail/.counter when minting short-uuids"
    (let [fs* (nexus/get :fs)]
      (fs/mkdirs fs* "/test/isaac/hail/delivered")
      (fs/spit fs* "/test/isaac/hail/delivered/hail-1.edn"
               (pr-str {:id "hail-1" :thread-id "thread-7"}))
      (let [id (:id (sut/send! {:frequencies {:band "bean-pickup"} :from :cli}))]
        (should (short-uuid? id))
        (should-not (fs/exists? fs* "/test/isaac/hail/.counter")))))

  (it "sequential strategy resumes after existing hail-N files"
    (config/dangerously-install-config! {:hail-settings {:naming-strategy :sequential}} "spec")
    (let [fs* (nexus/get :fs)]
      (fs/mkdirs fs* "/test/isaac/hail/delivered")
      (fs/spit fs* "/test/isaac/hail/delivered/hail-1.edn"
               (pr-str {:id "hail-1" :thread-id "hail-1"}))
      (let [record (sut/send! {:frequencies {:band "bean-pickup"} :params {:n 2} :from :cli})]
        (should= "hail-2" (:id record))
        (should= "hail-2" (:thread-id record)))))

  (it "uuid strategy mints a full UUID hail id"
    (config/dangerously-install-config! {:hail-settings {:naming-strategy :uuid}} "spec")
    (let [record (sut/send! {:frequencies {:band "bean-pickup"} :from :cli})
          id     (:id record)]
      (should (full-uuid? id))
      (should= id (:thread-id record))))

  (it "inherits thread-id from reply-to and defaults to the new id otherwise"
    (let [fs* (nexus/get :fs)]
      (fs/mkdirs fs* "/test/isaac/hail/pending")
      (fs/spit fs* "/test/isaac/hail/pending/hail-42.edn"
               (pr-str {:id "hail-42" :thread-id "thread-7"}))
      (let [record (sut/send! {:frequencies {:band "bean-pickup"}
                               :reply-to    "hail-42"
                               :from        :cli})]
        (should= "thread-7" (:thread-id record))
        (should= "hail-42" (:reply-to record))
        (should (short-uuid? (:id record)))
        (should-not= "hail-42" (:id record)))))

  (it "persists effective band data on the hail record"
    (config/dangerously-install-config!
     {:hail {"bean-pickup" {:session-tags [:project/chess]
                            :reach        :one
                            :data         {:bean-repo "isaac"
                                           :bean-id   "{{bean-id}}"}}}}
     "spec")
    (let [record (sut/send! {:frequencies {:band "bean-pickup"}
                             :params    {:bean-id "isaac-iz3a"}
                             :from      :cli})]
      (should= {:bean-repo "isaac" :bean-id "isaac-iz3a"} (:data record))
      (should= (:data record) (:data (sut/read-pending (:id record))))))

  (it "uses the CLI root binding when nexus :root is unset"
    (let [fs* (fs/mem-fs)]
      (nexus/-with-nexus {:fs fs*}
        (binding [root/*root* "/target/test-state"]
          (let [id (:id (sut/send! {:frequencies {:session-tags #{:wip}} :prompt "go" :from :cli}))]
            (should (short-uuid? id))
            (should (fs/exists? fs* (str "/target/test-state/hail/pending/" id ".edn")))))))))
