(ns analytics.metrics-conflation-test
  (:require [clojure.test :refer [deftest is testing]]
            [analytics.impact-observation :as io]
            [analytics.metrics-conflation :as mc]))

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

;; Declared vocabulary — the caller stamps it, this contract never invents it.
(def vocab-v1
  {:tally/scholarly-citation :impact-dimension
   :tally/retraction :impact-dimension
   :tally/correction :impact-dimension
   :tally/policy-citation :impact-dimension
   :tally/views :attention
   :tally/funding-usd :funding})

(def obs-a
  (influence-observation
   [(signal {:content-hash "a"})]
   window-1 subject-a "mv-2026-09-01"))

(def obs-b-other-window
  (influence-observation
   [(signal {:observed-at 1700100100 :content-hash "c"})]
   window-2 subject-a "mv-2026-09-01"))

;; A record carrying an ATTENTION-classified tally key, injected verbatim.
(def obs-a-with-attention
  (assoc-in obs-a [:tallies :tally/views] 12))

;; A record carrying a FUNDING-classified tally key, injected verbatim.
(def obs-a-with-funding
  (assoc-in obs-a [:tallies :tally/funding-usd] 5000))

;; A record carrying a tally key the vocabulary does NOT declare.
(def obs-a-with-unknown-key
  (assoc-in obs-a [:tallies :tally/mystery-score] 3))

(defn accepted [result]
  (is (= :accepted (first result)))
  (second result))

(deftest clean-history-stays-inside-the-declared-vocabulary
  (let [record (accepted (mc/build-conflation-observation
                          "mcv-2026-09-03" vocab-v1 [obs-a obs-b-other-window]))]
    (testing "only declared impact-dimension keys appear, nothing else"
      (is (false? (get-in record [:flags :unknown-metric-keys-present])))
      (is (empty? (get-in record [:flags :unknown-metric-keys])))
      (is (empty? (get-in record [:flags :attention-keys-present])))
      (is (empty? (get-in record [:flags :funding-keys-present])))
      (is (= 2 (get-in record [:flags :identities]))))
    (testing "the conflation boundaries are hardwired, data or no data"
      (is (true? (get-in record [:flags :attention-impact-conversion-forbidden])))
      (is (true? (get-in record [:flags :funding-endorsement-inference-forbidden])))
      (is (nil? (:aggregate record)))
      (is (true? (:aggregation-forbidden record))))
    (testing "the declared vocabulary travels verbatim, sorted"
      (is (= vocab-v1 (:vocabulary record))))
    (testing "groups carry their records verbatim"
      (doseq [[_ g] (:groups record)]
        (is (= 1 (long (:record-count g))))
        (is (pos? (count (:impact-dimension-keys g))))))))

(deftest attention-key-flagged-never-converted-to-impact
  (let [record (accepted (mc/build-conflation-observation
                          "mcv-2026-09-03" vocab-v1 [obs-a-with-attention]))]
    (testing "the attention key is reported as attention, in the declared class"
      (is (= [:tally/views] (get-in record [:flags :attention-keys-present]))))
    (testing "the value travels verbatim in the group record, untouched"
      (is (= 12 (get-in (first (vals (:groups record)))
                        [:records 0 :tallies :tally/views])))
      (is (= [:tally/views] (get-in (first (vals (:groups record)))
                                    [:attention-keys]))))
    (testing "no impact score, no weighting, no aggregate is produced"
      (is (true? (get-in record [:flags :attention-impact-conversion-forbidden])))
      (is (nil? (:aggregate record)))
      (is (true? (:aggregation-forbidden record)))
      (is (empty? (:claims record))))))

(deftest funding-key-flagged-never-read-as-endorsement
  (let [record (accepted (mc/build-conflation-observation
                          "mcv-2026-09-03" vocab-v1 [obs-a-with-funding]))]
    (testing "the funding key is reported as funding, in the declared class"
      (is (= [:tally/funding-usd] (get-in record [:flags :funding-keys-present]))))
    (testing "the funding amount travels verbatim, implies nothing"
      (is (= 5000 (get-in (first (vals (:groups record)))
                          [:records 0 :tallies :tally/funding-usd])))
      (is (true? (get-in record [:flags :funding-endorsement-inference-forbidden])))
      (is (empty? (:claims record))))))

(deftest unknown-key-flagged-never-guessed-into-a-class
  (let [record (accepted (mc/build-conflation-observation
                          "mcv-2026-09-03" vocab-v1 [obs-a-with-unknown-key]))]
    (testing "the key absent from the vocabulary is flagged, verbatim"
      (is (true? (get-in record [:flags :unknown-metric-keys-present])))
      (is (= [:tally/mystery-score] (get-in record [:flags :unknown-metric-keys])))
      (is (= [:tally/mystery-score]
             (get-in (first (vals (:groups record))) [:unknown-keys]))))
    (testing "no class is assigned to the unknown key — assigning one would be the judgment"
      (is (not (contains? (get-in (first (vals (:groups record))) [:attention-keys])
                          :tally/mystery-score)))
      (is (not (contains? (get-in (first (vals (:groups record))) [:impact-dimension-keys])
                          :tally/mystery-score))))))

(deftest structural-refusals
  (testing "non-vector history is rejected whole"
    (is (= [:rejected :history-not-vector]
           (mc/build-conflation-observation "mcv" vocab-v1 (list obs-a)))))
  (testing "one non-conformant record poisons the whole audit"
    (is (= [:rejected :non-conformant-observation-in-history]
           (mc/build-conflation-observation "mcv" vocab-v1 [obs-a {:contract "x"}]))))
  (testing "a malformed vocabulary is refused — inventing classes is forbidden"
    (is (= [:rejected :malformed-vocabulary]
           (mc/build-conflation-observation "mcv" {} [obs-a])))
    (is (= [:rejected :malformed-vocabulary]
           (mc/build-conflation-observation "mcv" {:tally/x :impact-score} [obs-a])))
    (is (= [:rejected :malformed-vocabulary]
           (mc/build-conflation-observation "mcv" "views-are-attention" [obs-a]))))
  (testing "empty history is accepted but flagged empty"
    (let [record (accepted (mc/build-conflation-observation "mcv" vocab-v1 []))]
      (is (true? (get-in record [:flags :empty-history])))
      (is (zero? (get-in record [:flags :identities])))
      (is (nil? (:coverage-window record))))))

(deftest determinism-byte-identical
  (let [a (mc/build-conflation-observation "mcv-2026-09-03" vocab-v1
                                           [obs-a obs-a-with-attention])
        b (mc/build-conflation-observation "mcv-2026-09-03" vocab-v1
                                           [obs-a obs-a-with-attention])]
    (is (= (pr-str a) (pr-str b)))))

(deftest time-window-refresh-append-only
  (let [r1 (accepted (mc/build-conflation-observation
                      "mcv-2026-09-03" vocab-v1 [obs-a]))
        h1 (mc/refresh [] r1)
        r2 (accepted (mc/build-conflation-observation
                      "mcv-2026-09-03" vocab-v1 [obs-a obs-a-with-attention]))
        h2 (mc/refresh h1 r2)]
    (testing "prior records are immutable and carried forward"
      (is (= [r1 r2] (mc/history-records h2)))
      (is (= r1 (first (mc/history-records h2)))))))

(deftest hyakka-proposal-and-readback
  (let [record (accepted (mc/build-conflation-observation
                          "mcv-2026-09-03" vocab-v1 [obs-a obs-a-with-attention]))
        proposal (mc/hyakka-proposal record)]
    (testing "proposal carries the boundary claims"
      (is (some? (:proposal/dedupe-key proposal)))
      (is (= vocab-v1 (:proposal/vocabulary proposal)))
      (is (true? (:proposal/ranking-forbidden proposal)))
      (is (true? (:proposal/causal-claims-forbidden proposal)))
      (is (true? (:proposal/aggregation-forbidden proposal)))
      (is (nil? (:proposal/aggregate proposal)))
      (is (empty? (:proposal/claims proposal)))
      (is (true? (get-in proposal [:proposal/flags :attention-impact-conversion-forbidden])))
      (is (true? (get-in proposal [:proposal/flags :funding-endorsement-inference-forbidden]))))
    (testing "faithful readback is accepted"
      (is (true? (mc/hyakka-readback-accept? proposal proposal))))
    (testing "tampered readback is refused"
      (is (false? (mc/hyakka-readback-accept?
                   proposal (assoc proposal :proposal/dedupe-key "tampered"))))
      (is (false? (mc/hyakka-readback-accept?
                   proposal (assoc proposal :proposal/ranking [{:rank 1}]))))
      (is (false? (mc/hyakka-readback-accept?
                   proposal (assoc proposal :proposal/aggregate {:impact-score 3.5}))))
      (is (false? (mc/hyakka-readback-accept?
                   proposal (dissoc proposal :proposal/aggregation-forbidden))))
      (is (false? (mc/hyakka-readback-accept?
                   proposal (update-in proposal [:proposal/flags]
                                       dissoc :attention-impact-conversion-forbidden))))
      (is (false? (mc/hyakka-readback-accept?
                   proposal (update-in proposal [:proposal/flags]
                                       dissoc :funding-endorsement-inference-forbidden))))
      (is (false? (mc/hyakka-readback-accept?
                   proposal (assoc-in proposal [:proposal/claims]
                                      [{:claim "views prove impact"}]))))
      (is (false? (mc/hyakka-readback-accept?
                   proposal (assoc proposal :proposal/vocabulary
                                   (assoc vocab-v1 :tally/views :impact-dimension)))))))
  (testing "empty history proposes nothing"
    (let [record (accepted (mc/build-conflation-observation "mcv" vocab-v1 []))]
      (is (nil? (mc/hyakka-proposal record))))))
