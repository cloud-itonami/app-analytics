(ns analytics.signal-currency-observation-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [analytics.signal-currency-observation :as scur]))

;; ---------------------------------------------------------------------------
;; Fixture builders — admitted-signal maps carrying dimension + provenance
;; ---------------------------------------------------------------------------

(defn- sig
  ([] (sig {}))
  ([{:keys [dimension observed-at hash url]
     :or {dimension :scholarly-citation
          observed-at 1000
          hash "h-1"
          url "https://example.org/a"}}]
   {:dimension dimension
    :observed-at observed-at
    :source/url url
    :source-url url
    :content-hash hash
    :source/source-class :citation-registry}))

(defn- currency-flags [audit] (:flags audit))

(deftest build-refusals
  ;; non-vector batch
  (is (= [:rejected :signals-not-vector]
         (scur/build-signal-currency-observation "mv-1" (list (sig)) {:as-of 2000 :stale-after 100})))
  ;; empty batch measures nothing — refused, not reported as "all current"
  (is (= [:rejected :empty-signal-batch]
         (scur/build-signal-currency-observation "mv-1" [] {:as-of 2000 :stale-after 100})))
  ;; missing method-version
  (is (= [:rejected :missing-method-version]
         (scur/build-signal-currency-observation "" [(sig)] {:as-of 2000 :stale-after 100})))
  (is (= [:rejected :missing-method-version]
         (scur/build-signal-currency-observation nil [(sig)] {:as-of 2000 :stale-after 100})))
  ;; missing/malformed currency scope refuses whole — no default horizon is
  ;; ever invented
  (is (= [:rejected :malformed-currency-scope]
         (scur/build-signal-currency-observation "mv-1" [(sig)] nil)))
  (is (= [:rejected :malformed-currency-scope]
         (scur/build-signal-currency-observation "mv-1" [(sig)] {})))
  (is (= [:rejected :malformed-currency-scope]
         (scur/build-signal-currency-observation "mv-1" [(sig)] {:as-of "2000" :stale-after 100})))
  (is (= [:rejected :malformed-currency-scope]
         (scur/build-signal-currency-observation "mv-1" [(sig)] {:as-of 2000 :stale-after nil})))
  (is (= [:rejected :malformed-currency-scope]
         (scur/build-signal-currency-observation "mv-1" [(sig)] {:as-of 2000 :stale-after -1}))))

(deftest stale-is-flagged-not-decayed
  ;; as-of 2000, stale-after 100: observed-at 1000 → age 900 → STALE;
  ;; observed-at 1980 → age 20 → current. The stale signal is counted and
  ;; kept — nothing is removed, discounted, or rewritten.
  (let [signals [(sig {:observed-at 1000 :hash "h-old"})
                 (sig {:observed-at 1980 :hash "h-new"})]
        [_ audit] (scur/build-signal-currency-observation "mv-1" signals {:as-of 2000 :stale-after 100})
        [d] (:dimensions (:currency audit))]
    ;; stale counted
    (is (= 1 (:stale-count d)))
    (is (= 1 (:current-count d)))
    (is (true? (:stale-signals-present (currency-flags audit))))
    ;; nothing removed
    (is (= 2 (:signal-count audit)))
    (is (= 2 (reduce (fn [acc d] (+ acc (long (:signal-count d))))
                     0 (:dimensions (:currency audit)))))
    ;; additive extremes — oldest and newest enumerated
    (is (= 1000 (:oldest-observed-at d)))
    (is (= 1980 (:newest-observed-at d)))
    (is (= 1000 (:oldest-age d)))
    ;; staleness is a measurement, not a removal: the record exposes no
    ;; decay mechanism at all
    (is (true? (:decay-forbidden (currency-flags audit))))))

(deftest boundary-of-stale-horizon-is-exclusive
  ;; age exactly == stale-after is NOT stale (strictly older than horizon).
  (let [signals [(sig {:observed-at 1900 :hash "h-edge"})]   ; age 100
        [_ audit] (scur/build-signal-currency-observation "mv-1" signals {:as-of 2000 :stale-after 100})
        [d] (:dimensions (:currency audit))]
    (is (= 0 (:stale-count d)))
    (is (= 1 (:current-count d)))))

(deftest per-dimension-tallies-are-independent
  ;; dimensions are audited independently; one stale dimension never
  ;; contaminates another's counts
  (let [signals [(sig {:dimension :scholarly-citation :observed-at 1000 :hash "h1"})
                 (sig {:dimension :policy-citation :observed-at 1990 :hash "h2"})
                 (sig {:dimension :policy-citation :observed-at 1995 :hash "h3"})]
        [_ audit] (scur/build-signal-currency-observation "mv-1" signals {:as-of 2000 :stale-after 100})
        dims (into {} (map (fn [d] [(:dimension d) d]) (:dimensions (:currency audit))))]
    (is (= 2 (count (:dimensions (:currency audit)))))
    (is (= 1 (:stale-count (:scholarly-citation dims))))
    (is (= 0 (:stale-count (:policy-citation dims))))
    (is (= 2 (:signal-count (:policy-citation dims))))
    ;; dimension entries are sorted by name — determinism is a fixture
    (is (= [:policy-citation :scholarly-citation]
           (mapv :dimension (:dimensions (:currency audit)))))))

(deftest unrecognized-signals-enumerated-not-counted
  ;; a signal missing the minimum shape is enumerated whole, never folded
  ;; into a count and never imputed a value
  (let [signals [(sig)
                 {:observed-at 1500}                                   ; no dimension
                 {:dimension :policy-citation}                          ; no observed-at
                 {:dimension :policy-citation :observed-at "1500"}]     ; malformed observed-at
        [_ audit] (scur/build-signal-currency-observation "mv-1" signals {:as-of 2000 :stale-after 100})
        flags (currency-flags audit)]
    (is (= 3 (:unrecognized-signals flags)))
    (is (= 3 (count (:unrecognized (:currency audit)))))
    ;; the conformant signal is still audited
    (is (= 1 (reduce (fn [acc d] (+ acc (long (:signal-count d))))
                     0 (:dimensions (:currency audit)))))
    (is (= 1 (:dimensions-measured flags)))))

(deftest stale-absence-is-not-freshness
  ;; all-current is a MEASUREMENT, not a freshness guarantee: the flags
  ;; distinguish the finding from a claim
  (let [signals [(sig {:observed-at 1999 :hash "h1"})]
        [_ audit] (scur/build-signal-currency-observation "mv-1" signals {:as-of 2000 :stale-after 100})
        flags (currency-flags audit)]
    (is (false? (:stale-signals-present flags)))
    (is (= 1 (:current-signals flags)))
    ;; coverage stays :partial — nothing about signals outside the batch
    (is (= :partial (:coverage flags)))
    (is (true? (:suggestion-only flags)))))

(deftest determinism-byte-identical
  ;; two runs over the same input produce byte-identical records, in any
  ;; batch order
  (let [signals [(sig {:observed-at 1000 :hash "h-old"})
                 (sig {:observed-at 1980 :hash "h-new"})
                 (sig {:dimension :policy-citation :observed-at 1950 :hash "h-mid"})]
        run1 (second (scur/build-signal-currency-observation "mv-1" signals {:as-of 2000 :stale-after 100}))
        run2 (second (scur/build-signal-currency-observation "mv-1" (into [] (reverse signals)) {:as-of 2000 :stale-after 100}))]
    (is (= (pr-str run1) (pr-str run2)))
    ;; dedupe-key is stable across runs and batch order
    (is (= (scur/dedupe-key run1) (scur/dedupe-key run2)))))

(deftest refresh-history-append-only
  (let [signals [(sig)]
        [_ audit] (scur/build-signal-currency-observation "mv-1" signals {:as-of 2000 :stale-after 100})
        history []
        history2 (scur/refresh history audit)
        [_ audit2] (scur/build-signal-currency-observation "mv-2" [(sig {:observed-at 1990 :hash "h9"})]
                                                           {:as-of 2100 :stale-after 50})
        history3 (scur/refresh history2 audit2)]
    ;; prior records immutable
    (is (= audit (first history3)))
    (is (= 2 (count history3)))
    (is (= audit2 (last history3)))
    ;; history readback never mutates
    (is (= history3 (scur/history-records history3)))))

(deftest hyakka-proposal-nil-for-empty
  ;; an audit over zero signals proposes nothing — absence is not zero
  (is (nil? (scur/hyakka-proposal nil)))
  (is (nil? (scur/hyakka-proposal {:signal-count 0 :currency {:dimensions []} :flags {}}))))

(deftest hyakka-proposal-and-readback
  (let [signals [(sig {:observed-at 1000 :hash "h-old"})
                 (sig {:observed-at 1980 :hash "h-new"})
                 (sig {:dimension :retraction :observed-at 1950 :hash "h-r"})]
        [_ audit] (scur/build-signal-currency-observation "mv-1" signals {:as-of 2000
                                                                          :stale-after 100})
        proposal (scur/hyakka-proposal audit)]
    ;; proposal carries the structural refusals
    (is (some? proposal))
    (is (= "signal-currency-observation/v1" (:proposal/contract proposal)))
    (is (true? (:proposal/decay-forbidden proposal)))
    (is (true? (:proposal/ranking-forbidden proposal)))
    (is (true? (:proposal/causal-claims-forbidden proposal)))
    (is (nil? (:proposal/ranking proposal)))
    (is (empty? (:proposal/claims proposal)))
    (is (= :partial (:proposal/coverage proposal)))
    ;; readback of the identical payload accepted
    (is (true? (scur/hyakka-readback-accept? proposal proposal)))
    ;; tampered readback refused
    (is (false? (scur/hyakka-readback-accept?
                 proposal (assoc proposal :proposal/decay-forbidden false))))
    (is (false? (scur/hyakka-readback-accept?
                 proposal (assoc proposal :proposal/ranking [{:r 1}]))))
    (is (false? (scur/hyakka-readback-accept?
                 proposal (assoc proposal :proposal/claims ["causal claim"]))))
    (is (false? (scur/hyakka-readback-accept?
                 proposal (dissoc proposal :proposal/dedupe-key))))
    (is (false? (scur/hyakka-readback-accept?
                 proposal (assoc proposal :proposal/coverage :complete))))
    (is (false? (scur/hyakka-readback-accept?
                 proposal (update proposal :proposal/signal-count inc))))
    (is (false? (scur/hyakka-readback-accept?
                 proposal (assoc proposal :proposal/method-version "mv-2"))))
    (is (false? (scur/hyakka-readback-accept?
                 proposal (update proposal :proposal/currency dissoc :dimensions))))))
