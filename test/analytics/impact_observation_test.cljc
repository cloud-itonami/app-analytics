(ns analytics.impact-observation-test
  (:require [clojure.test :refer [deftest is testing]]
            [analytics.impact-observation :as io]))

;; Fixed fixtures — no clock, no randomness. epoch seconds are literals.
(def window {:from 1700000000 :to 1700100000})

(def good-signal
  {:dimension :scholarly-citation
   :source/source-class :citation-registry
   :source-url "https://doi.crossref.example/10.1/x"
   :observed-at 1700000100
   :content-hash "abc123"})

(defn signal [overrides]
  (merge good-signal overrides))

(defn observation [signals method-version]
  (io/build-observation method-version (io/partition-signals signals window)
                        window {:subject/id "work:10.1/x" :subject/type :research-work}))

(deftest provenance-admission
  (testing "mandatory provenance: source-url, observed-at, content-hash"
    (doseq [k [:source-url :observed-at :content-hash]]
      (let [[v r] (io/admit-signal (dissoc good-signal k) window)]
        (is (= :rejected v) (str k " missing should reject"))
        (is (= :missing-provenance r)))))
  (testing "allowed source classes admit"
    (doseq [sc [:citation-registry :official-policy-document :official-patent-record
                :standards-body-first-party :guideline-publisher-first-party
                :publisher-correction-or-retraction]]
      (is (= :admitted (first (io/admit-signal (signal {:source/source-class sc}) window))))))
  (testing "forbidden classes refuse outright (e.g. search snippets, generated prose)"
    (doseq [sc [:search-snippet :generated-summary :third-party-wiki-prose
                :scraped-profile :inferred-causality]]
      (is (= [:rejected :forbidden-source-class]
             (io/admit-signal (signal {:source/source-class sc}) window)))))
  (testing "well-formed but non-impact classes refuse as not-allow-listed"
    (is (= [:rejected :source-class-not-impact-allow]
           (io/admit-signal (signal {:source/source-class :public-broadcast}) window)))))

(deftest window-exclusion
  (testing "out-of-window signals are excluded AND enumerated, not dropped"
    (let [early (signal {:observed-at 1699999999 :content-hash "h1"})
          in    (signal {:content-hash "h2"})
          late  (signal {:observed-at 1700100001 :content-hash "h3"})
          p     (io/partition-signals [early in late] window)]
      (is (= 1 (count (:admitted p))))
      (is (= 2 (count (:excluded-out-of-window p))))
      (is (= #{1699999999 1700100001}
             (set (map :observed-at (:excluded-out-of-window p)))))))
  (testing "exclusions show up in the observation flags"
    (let [obs (observation [(signal {:observed-at 1 :content-hash "h"})
                            (signal {:content-hash "in-window"})] "m1")]
      (is (true? (get-in obs [:flags :excluded-signals-present]))))))

(deftest tally-determinism-and-additivity
  (let [sigs [(signal {:content-hash "a"})
              (signal {:dimension :retraction
                       :source/source-class :publisher-correction-or-retraction
                       :content-hash "b"})
              (signal {:dimension :correction
                       :source/source-class :publisher-correction-or-retraction
                       :content-hash "c"})
              (signal {:dimension :policy-citation
                       :source/source-class :official-policy-document
                       :content-hash "d"})]
        obs1 (observation sigs "mv-2026-09-01")
        obs2 (observation sigs "mv-2026-09-01")]
    (testing "byte-identical pr-str across builds (determinism fixture)"
      (is (= (pr-str obs1) (pr-str obs2))))
    (testing "additive tallies — corrections/retractions stand alone, never netted"
      (is (= 1 (:tally/scholarly-citation (:tallies obs1))))
      (is (= 1 (:tally/retraction (:tallies obs1))))
      (is (= 1 (:tally/correction (:tallies obs1))))
      (is (= 1 (:tally/policy-citation (:tallies obs1))))))
  (testing "a signal contributes exactly 1 — it is an observation event"
    (let [obs (observation [(signal {:content-hash "x"})] "m")]
      (is (= 1 (:tally/scholarly-citation (:tallies obs)))))))

(deftest uncertainty-and-missingness-flags
  (testing "empty window: nothing measured, not zero"
    (let [obs (observation [] "m")]
      (is (= 0 (:admitted-count obs)))
      (is (empty? (:tallies obs)))
      (is (true? (get-in obs [:flags :missing-is-unmeasured])))
      (is (= :partial (get-in obs [:flags :coverage])))))
  (testing "retraction and correction preserved as flags"
    (let [obs (observation [(signal {:dimension :retraction
                                     :source/source-class :publisher-correction-or-retraction
                                     :content-hash "r"})] "m")]
      (is (true? (get-in obs [:flags :retraction-present])))
      (is (false? (get-in obs [:flags :correction-present])))))
  (testing "single-source dependency flagged when all signals share one class"
    (let [obs (observation [(signal {:content-hash "a"})
                            (signal {:content-hash "b"})] "m")]
      (is (true? (get-in obs [:flags :single-source-dependency]))))
    (let [obs (observation [(signal {:content-hash "a"})
                            (signal {:content-hash "b"
                                     :source/source-class :official-policy-document
                                     :dimension :policy-citation})] "m")]
      (is (false? (get-in obs [:flags :single-source-dependency])))))
  (testing "duplicate content-hash flagged (same underlying signal double-counted)"
    (let [obs (observation [(signal {:content-hash "dup"})
                            (signal {:content-hash "dup"})] "m")]
      (is (true? (get-in obs [:flags :duplicate-content-hash]))))))

(deftest structural-refusals
  (testing "no ranking, no causal claims, no narrative — on every observation"
    (let [obs (observation [(signal {:content-hash "a"})] "m")]
      (is (nil? (:ranking obs)))
      (is (true? (:ranking-forbidden obs)))
      (is (true? (:causal-claims-forbidden obs)))
      (is (= [] (:claims obs)))))
  (testing "method-version and window stamped on every observation"
    (let [obs (observation [(signal {:content-hash "a"})] "mv-9")]
      (is (= "mv-9" (:method-version obs)))
      (is (= window (:window obs)))
      (is (= "influence-observation/v1" (str (:contract obs) "/" (:version obs)))))))

(deftest refresh-history-is-append-only
  (let [h0 []
        o1 (observation [(signal {:content-hash "a"})] "m")
        h1 (io/refresh h0 o1)
        o2 (observation [(signal {:content-hash "a"})
                         (signal {:content-hash "b"})] "m")
        h2 (io/refresh h1 o2)]
    (testing "history grows; earlier entries unchanged"
      (is (= 2 (count h2)))
      (is (= o1 (first h2)))
      (is (= o2 (second h2))))
    (testing "prior observation object itself is immutable (same value)"
      (is (= o1 (first (io/history-observations h2)))))
    ;; h0/h1 untouched — no mutation path exercised on them
    ))

(deftest hyakka-proposal-and-readback
  (let [obs (observation [(signal {:content-hash "a"})
                          (signal {:dimension :retraction
                                   :source/source-class :publisher-correction-or-retraction
                                   :content-hash "b"})] "mv-1")
        proposal (io/hyakka-proposal obs)]
    (testing "proposal only when something was measured"
      (is (some? proposal))
      (is (= "influence-observation/v1" (:proposal/contract proposal)))
      (is (= :partial (:proposal/coverage proposal))))
    (testing "absence is nil, never a fabricated observation"
      (is (nil? (io/hyakka-proposal (observation [] "mv-1")))))
    (let [readback proposal]
      (is (true? (io/hyakka-readback-accept? proposal readback))))
    (testing "tampering is refused"
      (is (false? (io/hyakka-readback-accept? proposal
                                              (assoc-in proposal [:proposal/tallies :tally/scholarly-citation] 99))))
      (is (false? (io/hyakka-readback-accept? proposal
                                              (dissoc proposal :proposal/method-version))))
      (is (false? (io/hyakka-readback-accept? proposal
                                              (assoc-in proposal [:proposal/contract] "universal-score/v9"))))
      (is (false? (io/hyakka-readback-accept? proposal
                                              (assoc-in proposal [:proposal/flags :missing-is-unmeasured] false))))
      (testing "stripped structural guards are refused"
        (doseq [tampered [(dissoc proposal :proposal/ranking-forbidden)
                          (dissoc proposal :proposal/causal-claims-forbidden)
                          (assoc proposal :proposal/ranking [{:subject/id "w1" :rank 1}])
                          (assoc proposal :proposal/claims ["work X caused adoption Y"])]]
          (is (false? (io/hyakka-readback-accept? proposal tampered))))))))
