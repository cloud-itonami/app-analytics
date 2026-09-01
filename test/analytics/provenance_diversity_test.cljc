(ns analytics.provenance-diversity-test
  (:require [clojure.test :refer [deftest is testing]]
            [analytics.impact-observation :as io]
            [analytics.provenance-diversity :as pd]))

;; Fixed fixtures — no clock, no randomness. epoch seconds are literals.

(def window-1 {:from 1700000000 :to 1700100000})
(def window-2 {:from 1700100001 :to 1700200000})

(defn signal [overrides]
  (merge {:dimension :scholarly-citation
          :source/source-class :citation-registry
          :source-url "https://doi.crossref.example/10.1/x"
          :observed-at 1700000100
          :content-hash "abc123"}
         overrides))

(defn- build [method-version signals window]
  (pd/build-observation method-version (io/partition-signals signals window)
                        window {:subject/id "work:10.1/x" :subject/type :research-work}))

(defn- obs [method-version signals window]
  (second (build method-version signals window)))

(def obs-single-source
  (obs "pd-mv-1"
       [(signal {:content-hash "a"})
        (signal {:content-hash "b"})]
       window-1))

(def obs-multi-source
  (obs "pd-mv-1"
       [(signal {:content-hash "a"})
        (signal {:content-hash "b"
                 :source/source-class :official-patent-record
                 :source-url "https://patents.example/p1"
                 :observed-at 1700000200})]
       window-1))

(deftest input-validation
  (testing "a signal with missing provenance is refused upstream and enumerated, never averaged in"
    (let [r (obs "pd-mv-1" [(signal {:content-hash "a"})
                            (dissoc (signal {:content-hash "b"}) :content-hash)]
                 window-1)]
      (is (= {:missing-provenance 1} (get-in r [:flags :rejected-reasons])))
      (is (= 1 (:admitted-count r)))))
  (testing "a signal from a non-allow source class is refused upstream and enumerated"
    (let [r (obs "pd-mv-1" [(signal {:content-hash "a"})
                            (signal {:content-hash "b"
                                     :source/source-class :third-party-wiki-prose})]
                 window-1)]
      (is (= {:forbidden-source-class 1} (get-in r [:flags :rejected-reasons])))
      (is (= 1 (:admitted-count r)))))
  (testing "an unknown dimension is refused upstream and enumerated"
    (let [r (obs "pd-mv-1" [(signal {:content-hash "a"})
                            (signal {:content-hash "b" :dimension :vibes})]
                 window-1)]
      (is (= {:unknown-dimension 1} (get-in r [:flags :rejected-reasons])))
      (is (= 1 (:admitted-count r)))))
  (testing "malformed window is refused, not repaired"
    (is (= [:rejected :malformed-window]
           (pd/build-observation "pd-mv-1"
                                 (io/partition-signals [] window-1)
                                 {:from 100 :to 100}
                                 {:subject/id "w"}))))
  (testing "non-vector admitted bucket is refused"
    (is (= [:rejected :admitted-not-vector]
           (pd/build-observation "pd-mv-1" {:admitted '()} window-1 {:subject/id "w"})))))

(deftest composition-is-additive-and-sorted
  (let [r obs-multi-source]
    (testing "per-source-class counts are additive, one per admitted signal"
      (is (= {:citation-registry 1 :official-patent-record 1}
             (get-in r [:composition :source-class-counts]))))
    (testing "dimension×source-class matrix is present and sorted"
      (is (= {[:scholarly-citation :citation-registry] 1
              [:scholarly-citation :official-patent-record] 1}
             (get-in r [:composition :dimension-source-matrix]))))
    (testing "no cross-class aggregate number exists anywhere in the record"
      (is (nil? (:diversity-score r)))
      (is (true? (:diversity-score-forbidden r)))
      (is (nil? (some (fn [[k v]] (when (and (number? v) (not= k :admitted-count)) k))
                      (dissoc r :flags)))))))

(deftest corroboration-is-presence-not-agreement
  (testing "two distinct source classes with distinct hashes are corroborated"
    (is (= :multi-source-corroborated
           (get (:corroboration obs-multi-source) :scholarly-citation))))
  (testing "one source class only is not corroborated, even with many signals"
    (is (= :not-corroborated
           (get (:corroboration obs-single-source) :scholarly-citation))))
  (testing "two source classes repeating ONE content hash is not corroboration"
    (let [r (obs "pd-mv-1"
                 [(signal {:content-hash "same"})
                  (signal {:content-hash "same"
                           :source/source-class :official-patent-record
                           :source-url "https://patents.example/p2"
                           :dimension :patent-citation})]
                 window-1)]
      (is (= :not-corroborated (get (:corroboration r) :patent-citation)))
      (testing "but the echo is flagged, never silently merged"
        (is (true? (get-in r [:flags :same-artifact-via-multiple-sources])))))))

(deftest missing-data-flags
  (testing "empty admitted batch still yields a full flag set, never zeros"
    (let [r (obs "pd-mv-1" [] window-1)]
      (is (true? (get-in r [:flags :missing-is-unmeasured])))
      (is (false? (get-in r [:flags :single-source-dependency])))
      (is (= 0 (get-in r [:flags :source-class-count])))
      (is (= :partial (get-in r [:flags :coverage])))))
  (testing "excluded out-of-window signals are enumerated for audit"
    (let [partitioned (io/partition-signals
                       [(signal {:content-hash "a"})
                        (signal {:content-hash "x" :observed-at 1600000000})]
                       window-1)
          r (second (pd/build-observation "pd-mv-1" partitioned window-1
                                          {:subject/id "w"}))]
      (is (true? (get-in r [:flags :excluded-signals-present])))
      (is (= 1 (count (get-in r [:flags :excluded-out-of-window]))))
      (is (= 1 (:admitted-count r))))))

(deftest determinism-and-dedupe
  (let [a (obs "pd-mv-1"
               [(signal {:content-hash "a"})
                (signal {:content-hash "b"
                         :source/source-class :official-patent-record
                         :source-url "https://patents.example/p1"
                         :dimension :patent-citation})]
               window-1)
        b (obs "pd-mv-1"
               [(signal {:content-hash "a"})
                (signal {:content-hash "b"
                         :source/source-class :official-patent-record
                         :source-url "https://patents.example/p1"
                         :dimension :patent-citation})]
               window-1)]
    (testing "same input, byte-identical record"
      (is (= (pr-str a) (pr-str b))))
    (testing "same input, same dedupe key"
      (is (= (pd/dedupe-key a) (pd/dedupe-key b))))
    (testing "different window, different key"
      (is (not= (pd/dedupe-key a)
                (pd/dedupe-key (obs "pd-mv-1"
                                    [(signal {:content-hash "a"
                                              :observed-at 1700100100})
                                     (signal {:content-hash "b"
                                              :observed-at 1700100200
                                              :source/source-class :official-patent-record
                                              :source-url "https://patents.example/p1"
                                              :dimension :patent-citation})]
                                    window-2)))))))

(deftest append-only-refresh
  (let [h1 (pd/refresh [] obs-single-source)
        h2 (pd/refresh h1 obs-multi-source)]
    (testing "prior records are immutable and history never shrinks"
      (is (= 1 (count h1)))
      (is (= 2 (count h2)))
      (is (= (first h1) (first h2))))
    (testing "nothing in the prior record is rewritten"
      (is (identical? obs-single-source (first h2))))
    (testing "history-records returns the append-only list"
      (is (= h2 (pd/history-records h2))))))

(deftest hyakka-proposal-and-readback
  (testing "empty batch proposes nothing"
    (is (nil? (pd/hyakka-proposal (obs "pd-mv-1" [] window-1)))))
  (let [proposal (pd/hyakka-proposal obs-multi-source)]
    (testing "proposal carries the structural refusals"
      (is (true? (:proposal/ranking-forbidden proposal)))
      (is (true? (:proposal/causal-claims-forbidden proposal)))
      (is (true? (:proposal/diversity-score-forbidden proposal)))
      (is (nil? (:proposal/diversity-score proposal)))
      (is (empty? (:proposal/claims proposal)))
      (is (= :partial (:proposal/coverage proposal))))
    (testing "faithful readback is accepted"
      (is (true? (pd/hyakka-readback-accept? proposal proposal))))
    (testing "stripped refusals are tampering — refused"
      (is (false? (pd/hyakka-readback-accept?
                   proposal (dissoc proposal :proposal/diversity-score-forbidden))))
      (is (false? (pd/hyakka-readback-accept?
                   proposal (assoc proposal :proposal/ranking [{:who "x"}]))))
      (is (false? (pd/hyakka-readback-accept?
                   proposal (assoc proposal :proposal/claims ["caused adoption"])))))
    (testing "altered composition is tampering — refused"
      (is (false? (pd/hyakka-readback-accept?
                   proposal (assoc-in proposal
                                      [:proposal/composition :source-class-counts
                                       :citation-registry] 99)))))))
