(ns analytics.observation-conflict-test
  (:require [clojure.test :refer [deftest is testing]]
            [analytics.impact-observation :as io]
            [analytics.observation-conflict :as oc]))

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

(defn influence-observation [signals window subject method-version]
  (io/build-observation method-version (io/partition-signals signals window)
                        window subject))

(def subject-a {:subject/id "work:10.1/x" :subject/type :research-work})

;; Two independent records for the same identity with DIFFERENT tallies.
(def obs-a-1
  (influence-observation
   [(signal {:content-hash "a"})]
   window-1 subject-a "mv-2026-09-01"))

(def obs-a-2-conflicting
  (influence-observation
   [(signal {:content-hash "a"})
    (signal {:content-hash "b" :observed-at 1700000200
             :dimension :retraction
             :source/source-class :publisher-correction-or-retraction
             :source-url "https://doi.crossref.example/10.1/x/r1"})]
   window-1 subject-a "mv-2026-09-01"))

;; Same identity, same tallies — a duplicate repeat, not a conflict.
(def obs-a-duplicate obs-a-1)

;; Different identity (different window): not in conflict with obs-a-1.
(def obs-a-other-window
  (influence-observation
   [(signal {:observed-at 1700100100 :content-hash "c"})]
   window-2 subject-a "mv-2026-09-01"))

(defn accepted [result]
  (is (= :accepted (first result)))
  (second result))

(deftest agreeing-identity-is-clean
  (let [record (accepted (oc/build-conflict-observation
                          "oca-2026-09-03" [obs-a-1 obs-a-other-window]))]
    (testing "distinct identities with one record each do not conflict"
      (is (zero? (get-in record [:flags :conflicting-groups])))
      (is (empty? (get-in record [:flags :conflicting-identities])))
      (is (= 2 (get-in record [:flags :single-record-groups])))
      (is (false? (get-in record [:flags :duplicate-records-present]))))
    (testing "each group carries its single record verbatim"
      (doseq [[_ g] (:groups record)]
        (is (= 1 (long (:record-count g))))
        (is (false? (:conflicting g)))
        (is (nil? (:reconciliation g)))
        (is (true? (:reconciliation-forbidden g)))))))

(deftest conflicting-records-flagged-and-preserved-verbatim
  (let [record (accepted (oc/build-conflict-observation
                          "oca-2026-09-03" [obs-a-1 obs-a-2-conflicting]))
        conflict-ids (get-in record [:flags :conflicting-identities])
        group (get (:groups record) (first conflict-ids))]
    (testing "the conflict is flagged, never averaged away"
      (is (= 1 (get-in record [:flags :conflicting-groups])))
      (is (true? (:conflicting group))))
    (testing "both tallies variants travel verbatim, additive, distinct"
      (is (= 2 (count (:distinct-tallies-variants group))))
      (is (contains? (set (:distinct-tallies-variants group))
                     (pr-str (into (sorted-map) (:tallies obs-a-1)))))
      (is (contains? (set (:distinct-tallies-variants group))
                     (pr-str (into (sorted-map) (:tallies obs-a-2-conflicting))))))
    (testing "no reconciliation and no aggregate is produced"
      (is (nil? (:reconciliation group)))
      (is (true? (:reconciliation-forbidden group)))
      (is (nil? (:aggregate record)))
      (is (true? (:aggregation-forbidden record))))
    (testing "counts stay additive — both records are still there"
      (is (= 2 (:history-size record)))
      (is (= 2 (long (:record-count group)))))))

(deftest duplicates-reported-not-silently-deduplicated
  (let [record (accepted (oc/build-conflict-observation
                          "oca-2026-09-03" [obs-a-1 obs-a-duplicate]))
        [_ g] (first (:groups record))]
    (testing "identical repeats are flagged as duplicates"
      (is (true? (get-in record [:flags :duplicate-records-present])))
      (is (= 2 (long (:record-count g))))
      (is (false? (:conflicting g))))
    (testing "counts stay additive — the repeat is NOT dropped"
      (is (= 2 (:history-size record)))
      (is (= 2 (count (:records g)))))))

(deftest structural-refusals
  (testing "non-vector history is rejected whole"
    (is (= [:rejected :history-not-vector]
           (oc/build-conflict-observation "oca" (list obs-a-1)))))
  (testing "one non-conformant record poisons the whole audit"
    (is (= [:rejected :non-conformant-observation-in-history]
           (oc/build-conflict-observation "oca" [obs-a-1 {:contract "x"}]))))
  (testing "empty history is accepted but flagged empty"
    (let [record (accepted (oc/build-conflict-observation "oca" []))]
      (is (true? (get-in record [:flags :empty-history])))
      (is (zero? (get-in record [:flags :conflicting-groups])))
      (is (nil? (:coverage-window record))))))

(deftest determinism-byte-identical
  (let [a (oc/build-conflict-observation "oca-2026-09-03" [obs-a-1 obs-a-2-conflicting])
        b (oc/build-conflict-observation "oca-2026-09-03" [obs-a-1 obs-a-2-conflicting])]
    (is (= (pr-str a) (pr-str b)))))

(deftest time-window-refresh-append-only
  (let [r1 (accepted (oc/build-conflict-observation
                      "oca-2026-09-03" [obs-a-1]))
        h1 (oc/refresh [] r1)
        r2 (accepted (oc/build-conflict-observation
                      "oca-2026-09-03" [obs-a-1 obs-a-2-conflicting]))
        h2 (oc/refresh h1 r2)]
    (testing "prior records are immutable and carried forward"
      (is (= [r1 r2] (oc/history-records h2)))
      (is (= r1 (first (oc/history-records h2)))))))

(deftest hyakka-proposal-and-readback
  (let [record (accepted (oc/build-conflict-observation
                          "oca-2026-09-03" [obs-a-1 obs-a-2-conflicting]))
        proposal (oc/hyakka-proposal record)]
    (testing "proposal carries the boundary claims"
      (is (some? (:proposal/dedupe-key proposal)))
      (is (true? (:proposal/ranking-forbidden proposal)))
      (is (true? (:proposal/causal-claims-forbidden proposal)))
      (is (true? (:proposal/aggregation-forbidden proposal)))
      (is (nil? (:proposal/aggregate proposal)))
      (is (empty? (:proposal/claims proposal))))
    (testing "faithful readback is accepted"
      (is (true? (oc/hyakka-readback-accept? proposal proposal))))
    (testing "tampered readback is refused"
      (is (false? (oc/hyakka-readback-accept?
                   proposal (assoc proposal :proposal/dedupe-key "tampered"))))
      (is (false? (oc/hyakka-readback-accept?
                   proposal (assoc proposal :proposal/ranking [{:rank 1}]))))
      (is (false? (oc/hyakka-readback-accept?
                   proposal (assoc proposal :proposal/aggregate {:mean 3.5}))))
      (is (false? (oc/hyakka-readback-accept?
                   proposal (dissoc proposal :proposal/aggregation-forbidden))))
      (is (false? (oc/hyakka-readback-accept?
                   proposal (assoc-in proposal [:proposal/claims]
                                      [{:claim "agreement proves accuracy"}]))))
      (is (false? (oc/hyakka-readback-accept?
                   proposal (update-in proposal [:proposal/flags]
                                       dissoc :conflicting-groups))))))
  (testing "empty history proposes nothing"
    (let [record (accepted (oc/build-conflict-observation "oca" []))]
      (is (nil? (oc/hyakka-proposal record))))))
