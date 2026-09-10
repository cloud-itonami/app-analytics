(ns analytics.method-drift-observation-test
  (:require [clojure.test :refer [deftest is testing]]
            [analytics.impact-observation :as io]
            [analytics.method-drift-observation :as md]))

;; Fixed fixtures — no clock, no randomness. epoch seconds are literals.

(def window-1 {:from 1700000000 :to 1700100000})
(def window-2 {:from 1700100001 :to 1700200000})
(def window-overlap {:from 1700050000 :to 1700150000}) ; overlaps both

(defn signal [overrides]
  (merge {:dimension :scholarly-citation
          :source/source-class :citation-registry
          :source-url "https://doi.crossref.example/10.1/x"
          :observed-at 1700000100
          :content-hash "abc123"}
         overrides))

(defn influence-observation [signals window subject method-version]
  (io/build-observation method-version (io/partition-signals signals window)
                        window subject))

(def subject-a {:subject/id "work:10.1/x" :subject/type :research-work})
(def subject-b {:subject/id "work:10.1/y" :subject/type :research-work})

(def obs-mv1
  (influence-observation
   [(signal {:content-hash "a"})
    (signal {:dimension :retraction
             :source/source-class :publisher-correction-or-retraction
             :content-hash "b"})]
   window-1 subject-a "mv-2026-09-01"))

(def obs-mv2
  (influence-observation
   [(signal {:observed-at 1700100100 :content-hash "c"})
    (signal {:observed-at 1700100200 :content-hash "d"
             :dimension :policy-citation
             :source/source-class :official-policy-document})]
   window-2 subject-a "mv-2026-09-02"))

(def obs-mv1-later
  (influence-observation
   [(signal {:observed-at 1700100300 :content-hash "e"})]
   window-2 subject-a "mv-2026-09-01"))

(def obs-overlap
  (influence-observation
   [(signal {:observed-at 1700060000 :content-hash "f"})]
   window-overlap subject-a "mv-2026-09-01"))

(def obs-subject-b
  (influence-observation
   [(signal {:observed-at 1700100400 :content-hash "g"})]
   window-2 subject-b "mv-2026-09-01"))

(defn accepted [result]
  (is (= :accepted (first result)))
  (second result))

(deftest single-version-comparable-history
  (let [record (accepted (md/build-method-drift-observation
                          "mda-2026-09-03" [obs-mv1 obs-mv1-later]))]
    (testing "one method-version across the whole history is comparable"
      (is (= ["mv-2026-09-01"] (:observed-method-versions record)))
      (is (false? (:mixed-method-versions (:flags record))))
      (is (true? (:comparable (:flags record)))))
    (testing "provenance is preserved verbatim, per-version sizes reported"
      (is (= {"mv-2026-09-01" 2} (:per-version-history-size record))))
    (testing "structural refusals are hardwired"
      (is (nil? (:ranking record)))
      (is (true? (:ranking-forbidden record)))
      (is (true? (:causal-claims-forbidden record)))
      (is (empty? (:claims record)))
      (is (nil? (:cross-version-aggregate record)))
      (is (true? (:cross-version-aggregation-forbidden record)))
      (is (= :partial (:coverage (:flags record)))))))

(deftest mixed-method-versions-flagged-never-averaged
  (let [record (accepted (md/build-method-drift-observation
                          "mda-2026-09-03" [obs-mv1 obs-mv2]))]
    (testing "mixed versions produce the flag, no aggregate is produced"
      (is (= ["mv-2026-09-01" "mv-2026-09-02"] (:observed-method-versions record)))
      (is (true? (:mixed-method-versions (:flags record))))
      (is (false? (:comparable (:flags record))))
      (is (nil? (:cross-version-aggregate record)))
      (is (true? (:cross-version-aggregation-forbidden record))))
    (testing "per-version sizes stay additive and separate"
      (is (= {"mv-2026-09-01" 1 "mv-2026-09-02" 1}
             (:per-version-history-size record))))))

(deftest overlapping-windows-flagged-never-netted
  (let [record (accepted (md/build-method-drift-observation
                          "mda-2026-09-03" [obs-mv1 obs-overlap]))]
    (is (true? (:overlapping-windows-present (:flags record))))
    (is (false? (:comparable (:flags record))))
    (testing "counts are additive — no de-duplication or netting occurred"
      (is (= 2 (:history-size record))))))

(deftest mixed-subjects-flagged-identity-preserved
  (let [record (accepted (md/build-method-drift-observation
                          "mda-2026-09-03" [obs-mv1 obs-subject-b]))]
    (is (true? (:mixed-subjects (:flags record))))
    (is (= ["work:10.1/x" "work:10.1/y"] (:subjects record)))
    (is (false? (:comparable (:flags record))))))

(deftest structural-refusals
  (testing "non-vector history is rejected whole"
    (is (= [:rejected :history-not-vector]
           (md/build-method-drift-observation "mda" (list obs-mv1)))))
  (testing "one non-conformant record poisons the whole audit"
    (is (= [:rejected :non-conformant-observation-in-history]
           (md/build-method-drift-observation "mda" [obs-mv1 {:contract "x"}]))))
  (testing "empty history is accepted but flagged empty and not comparable"
    (let [record (accepted (md/build-method-drift-observation "mda" []))]
      (is (true? (get-in record [:flags :empty-history])))
      (is (false? (get-in record [:flags :comparable])))
      (is (nil? (:coverage-window record))))))

(deftest determinism-byte-identical
  (let [a (md/build-method-drift-observation "mda-2026-09-03" [obs-mv1 obs-mv2])
        b (md/build-method-drift-observation "mda-2026-09-03" [obs-mv1 obs-mv2])]
    (is (= (pr-str a) (pr-str b)))))

(deftest time-window-refresh-append-only
  (let [r1 (accepted (md/build-method-drift-observation
                      "mda-2026-09-03" [obs-mv1]))
        h1 (md/refresh [] r1)
        r2 (accepted (md/build-method-drift-observation
                      "mda-2026-09-03" [obs-mv1 obs-mv2]))
        h2 (md/refresh h1 r2)]
    (testing "prior records are immutable and carried forward"
      (is (= [r1 r2] (md/history-records h2)))
      (is (= r1 (first (md/history-records h2)))))))

(deftest hyakka-proposal-and-readback
  (let [record (accepted (md/build-method-drift-observation
                          "mda-2026-09-03" [obs-mv1 obs-mv2]))
        proposal (md/hyakka-proposal record)]
    (testing "proposal carries the boundary claims"
      (is (some? (:proposal/dedupe-key proposal)))
      (is (true? (:proposal/ranking-forbidden proposal)))
      (is (true? (:proposal/causal-claims-forbidden proposal)))
      (is (true? (:proposal/cross-version-aggregation-forbidden proposal)))
      (is (empty? (:proposal/claims proposal))))
    (testing "faithful readback is accepted"
      (is (true? (md/hyakka-readback-accept? proposal proposal))))
    (testing "tampered readback is refused"
      (is (false? (md/hyakka-readback-accept?
                   proposal (assoc proposal :proposal/dedupe-key "tampered"))))
      (is (false? (md/hyakka-readback-accept?
                   proposal (assoc proposal
                                   :proposal/ranking [{:rank 1}]))))
      (is (false? (md/hyakka-readback-accept?
                   proposal (dissoc proposal
                                    :proposal/cross-version-aggregation-forbidden))))
      (is (false? (md/hyakka-readback-accept?
                   proposal (assoc-in proposal [:proposal/claims]
                                      [{:claim "citation implies impact"}]))))))
  (testing "empty history proposes nothing"
    (let [record (accepted (md/build-method-drift-observation "mda" []))]
      (is (nil? (md/hyakka-proposal record))))))
