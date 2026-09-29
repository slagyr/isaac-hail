(ns isaac.hail.cli-spec
  "Bootstrap coverage for isaac-1i1x: a real shell invokes `isaac hail send`
   with nothing registered — no session store, no installed runtime. The
   feature suite runs the CLI in-process with a store the harness
   pre-registers, so only a unit test that starts from a truly empty nexus
   catches a missing host/ensure-runtime! call."
  (:require
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.hail.cli :as sut]
    [isaac.hail.queue :as queue]
    [isaac.nexus :as nexus]
    [isaac.session.store.spi :as store]
    [speclj.core :refer :all]))

(describe "hail cli bootstrap"

  (around [example]
    (nexus/-with-nexus {:fs (fs/mem-fs)}
      (example)))

  (it "has no session store registered before a command runs (sanity on the fixture itself)"
    (should-be-nil (store/registered-store)))

  (it "registers the session store before resolving/submitting a direct hail, as a fresh process requires"
    (with-redefs [loader/load-config! (fn [_ _ _] {:root "/test/isaac"})
                  queue/send!         (fn [record] (assoc record :id "turn-1"))]
      (should= 0 (#'sut/run-send {} ["--session-tag" "warp" "--prompt" "hi"])))
    (should-not-be-nil (store/registered-store)))

  (it "registers the session store on a --dry-run send too (still resolves before printing)"
    (with-redefs [loader/load-config! (fn [_ _ _] {:root "/test/isaac"})]
      (should= 0 (#'sut/run-send {} ["--session-tag" "warp" "--prompt" "hi" "--dry-run"])))
    (should-not-be-nil (store/registered-store)))

  (it "does not blow up with a nil-store protocol error when the runtime was never installed"
    (with-redefs [loader/load-config! (fn [_ _ _] {:root "/test/isaac"})]
      ;; No stub on queue/send! here: this runs the real submit path
      ;; (isaac.turn.submit/submit! -> isaac.session.store.spi/registered-store)
      ;; against a session-tag with no matching session. Before the fix this
      ;; raised IllegalArgumentException (no SessionPolicy impl for nil); the
      ;; fix turns that into a normal "no session" business error.
      (should= 1 (#'sut/run-send {} ["--session-tag" "warp" "--prompt" "hi"])))))
