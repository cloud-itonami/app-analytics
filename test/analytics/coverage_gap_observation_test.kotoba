(ns analytics.coverage-gap-observation-test
  (:require [clojure.test :refer [deftest is testing]]
            [analytics.impact-observation :as io]
            [analytics.coverage-gap-observation :as cg]))

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

(def obs-1
  (influence-observation
   [(signal {:content-hash "a"})
    (signal {:dimension :retraction
             :source/source-class :publisher-correction-or-retraction
             :content-hash "b"})]
   window-1
   {:subject/id "work:10.1/x" :subject/type :research-work}
   "mv-2026-09-01"))

(def obs-2
  (influence-observation
   [(signal {:observed-at 1700100100 :content-hash "c"})
    (signal {:observed-at 1700100200 :content-hash "d"
             :dimension :policy-citation
             :source/source-class :official-policy-document})]
   window-2
   {:subject/id "work:10.1/x" :subject/type :research-work}
   "mv-2026-09-01"))

(defn cov [history] (second (cg/build-coverage-observation "cg-mv-1" history)))

(deftest input-validation
  (testing "only influence-observation/v1-shaped records are consumed"
    (is (= [:rejected :non-conformant-observation-in-history]
           (cg/build-coverage-observation "cg" [(assoc obs-1 :contract "other")])))
    (is (= [:rejected :non-conformant-observation-in-history]
           (cg/build-coverage-observation "cg" [(dissoc obs-1 :ranking-forbidden)]))))
  (testing "history must be a vector, not a bare map or list"
    (is (= [:rejected :history-not-vector]
           (cg/build-coverage-observation "cg" {})))))

(deftest additive-rollup-no-netting
  (let [r (cov [obs-1 obs-2])]
    (testing "measured-in counts observations carrying a positive tally"
      (is (= 2 (get-in r [:coverage :dimensions :scholarly-citation :measured-in])))
      (is (= 1 (get-in r [:coverage :dimensions :retraction :measured-in])))
      (is (= 1 (get-in r [:coverage :dimensions :policy-citation :measured-in]))))
    (testing "unmeasured-in is missingness, never zero-valued evidence"
      (is (= 1 (get-in r [:coverage :dimensions :retraction :unmeasured-in])))
      (is (= 2 (get-in r [:coverage :dimensions :replication :unmeasured-in]))))
    (testing "counts are additive and never netted"
      (is (= 1 (get-in r [:flags :retraction-observations-count])))
      (is (= 0 (get-in r [:flags :correction-observations-count]))))))

(deftest missingness-flags
  (testing "empty history: empty-history flag, nothing derived"
    (let [r (cov [])]
      (is (true? (get-in r [:flags :empty-history])))
      (is (nil? (:coverage-window r)))
      (is (every? #(zero? (long (get-in r [:coverage :dimensions % :measured-in])))
                  cg/known-dimensions))))
  (testing "dimensions-never-measured enumerates absence explicitly"
    (let [r (cov [obs-1 obs-2])
          never (:dimensions-never-measured (:flags r))]
      (is (contains? (set never) :replication))
      (is (not (contains? (set never) :scholarly-citation)))))
  (testing "coverage is always :partial — a rollup is not a completeness claim"
    (is (= :partial (get-in (cov [obs-1]) [:flags :coverage])))
    (is (= :partial (:proposal/coverage (cg/hyakka-proposal (cov [obs-1])))))))

(deftest determinism-fixture
  (testing "byte-identical pr-str across builds over the same history"
    (let [a (cov [obs-1 obs-2])
          b (cov [obs-1 obs-2])]
      (is (= (pr-str a) (pr-str b))))
    (testing "dedupe key is order-independent across builds"
      (is (= (cg/dedupe-key (cov [obs-1 obs-2]))
             (cg/dedupe-key (cov [obs-1 obs-2])))))))

(deftest refresh-history-is-append-only
  (let [r1 (cov [obs-1])
        r2 (cov [obs-1 obs-2])
        h  (cg/refresh (cg/refresh [] r1) r2)]
    (is (= [r1 r2] h))
    (is (= [r1 r2] (cg/history-records h)))
    (testing "prior records are not mutated by refresh"
      (is (= r1 (first (cg/history-records h)))))))

(deftest hyakka-proposal-and-readback
  (let [r    (cov [obs-1 obs-2])
        prop (cg/hyakka-proposal r)]
    (testing "empty history proposes nothing (absence is not data)"
      (is (nil? (cg/hyakka-proposal (cov [])))))
    (testing "proposal carries structural refusals"
      (is (true? (:proposal/ranking-forbidden prop)))
      (is (true? (:proposal/causal-claims-forbidden prop)))
      (is (nil? (:proposal/ranking prop)))
      (is (empty? (:proposal/claims prop)))
      (is (some? (:proposal/dedupe-key prop))))
    (testing "clean readback is accepted"
      (is (true? (cg/hyakka-readback-accept? prop prop))))
    (testing "readback with stripped refusals is refused (tampering)"
      (is (false? (cg/hyakka-readback-accept?
                   prop (dissoc prop :proposal/ranking-forbidden))))
      (is (false? (cg/hyakka-readback-accept?
                   prop (assoc prop :proposal/ranking [{:rank 1}]))))
      (is (false? (cg/hyakka-readback-accept?
                   prop (assoc prop :proposal/claims ["caused impact"])))))
    (testing "readback with altered missingness is refused"
      (is (false? (cg/hyakka-readback-accept?
                   prop (assoc-in prop [:proposal/flags :missing-is-unmeasured] false)))))))

(deftest dedupe-key-stability
  (testing "same rollup -> same key; different history -> different key"
    (let [k1 (cg/dedupe-key (cov [obs-1]))
          k2 (cg/dedupe-key (cov [obs-1]))]
      (is (= k1 k2))
      (is (not= k1 (cg/dedupe-key (cov [obs-1 obs-2])))))
    (testing "key is prefixed with its contract identity"
      (is (.startsWith ^String (cg/dedupe-key (cov [obs-1]))
                       "coverage-gap-observation/v1:")))))
