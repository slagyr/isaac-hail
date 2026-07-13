(ns isaac.hail.beans-status-spec
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [isaac.hail.beans-status :as sut]
    [speclj.core :refer :all]))

(defn- bean-md [bean-id status]
  (str "---\n# " bean-id "\nstatus: " status "\n---\n"))

(defn- sh! [cmd & args]
  (let [{:keys [exit err]} (apply clojure.java.shell/sh cmd args)]
    (when (pos? (long exit))
      (throw (ex-info "shell failed" {:cmd cmd :args args :err err})))))

(describe "beans status (isaac-u91b)"
  (it "resolves git@github.com:slagyr/isaac.git to the isaac beans-repos entry"
    (let [delivery {:data {:bean-repo "git@github.com:slagyr/isaac.git"}}
          cfg      {:hail-settings {:beans-repos {:isaac "/configured/isaac"}}}]
      (should= "/configured/isaac"
               (#'sut/resolve-beans-dir cfg delivery))))

  (it "bean-completed? reads completed from origin/main when local checkout is behind (isaac-u91b)"
    (let [dir (.getAbsolutePath (doto (io/file (System/getProperty "java.io.tmpdir")
                                                 (str "isaac-hail-beans-u91b-" (System/nanoTime)))
                                     .mkdirs))]
      (try
        (sh! "git" "-C" dir "init")
        (sh! "git" "-C" dir "config" "user.email" "test@test")
        (sh! "git" "-C" dir "config" "user.name" "test")
        (.mkdirs (io/file dir ".beans"))
        (spit (str dir "/.beans/isaac-u91b--test.md") (bean-md "isaac-u91b" "in-progress"))
        (sh! "git" "-C" dir "add" ".")
        (sh! "git" "-C" dir "commit" "-m" "local in-progress")
        (let [{:keys [out]} (clojure.java.shell/sh "git" "-C" dir "rev-parse" "HEAD")
              local-sha (str/trim out)]
          (spit (str dir "/.beans/isaac-u91b--test.md") (bean-md "isaac-u91b" "completed"))
          (sh! "git" "-C" dir "add" ".")
          (sh! "git" "-C" dir "commit" "-m" "completed on main")
          (let [{:keys [out]} (clojure.java.shell/sh "git" "-C" dir "rev-parse" "HEAD")
                remote-sha (str/trim out)]
            (sh! "git" "-C" dir "update-ref" "refs/remotes/origin/main" remote-sha)
            (sh! "git" "-C" dir "reset" "--hard" local-sha)
            (let [cfg      {:hail-settings {:beans-repos {:isaac dir}}}
                  delivery {:data {:bean-repo "isaac"} :params {:bean-id "isaac-u91b"}}]
              (should (sut/bean-completed? cfg delivery "isaac-u91b"))
              (should-not (sut/turn-in-limbo? cfg delivery {:executed-tool-names #{}})))))
        (finally
          (io/delete-file (io/file dir) true)))))

  (it "turn-in-limbo? is true when bean is in-progress on origin (regression isaac-je45)"
    (let [dir (.getAbsolutePath (doto (io/file (System/getProperty "java.io.tmpdir")
                                                 (str "isaac-hail-beans-u91b-ip-" (System/nanoTime)))
                                     .mkdirs))]
      (try
        (sh! "git" "-C" dir "init")
        (sh! "git" "-C" dir "config" "user.email" "test@test")
        (sh! "git" "-C" dir "config" "user.name" "test")
        (.mkdirs (io/file dir ".beans"))
        (spit (str dir "/.beans/isaac-limbo--test.md") (bean-md "isaac-limbo" "in-progress"))
        (sh! "git" "-C" dir "add" ".")
        (sh! "git" "-C" dir "commit" "-m" "init")
        (let [{:keys [out]} (clojure.java.shell/sh "git" "-C" dir "rev-parse" "HEAD")
              sha (str/trim out)]
          (sh! "git" "-C" dir "update-ref" "refs/remotes/origin/main" sha)
          (let [cfg      {:hail-settings {:beans-repos {:isaac dir}}}
                delivery {:data {:bean-repo "isaac"} :params {:bean-id "isaac-limbo"}}]
            (should (sut/turn-in-limbo? cfg delivery {:executed-tool-names #{}}))))
        (finally
          (io/delete-file (io/file dir) true))))))
