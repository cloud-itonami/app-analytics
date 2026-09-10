(ns analytics.co-measurement-test
  (:require [clojure.test :refer [deftest is testing]]
            [analytics.impact-observation :as io]
            [analytics.co-measurement :as cm]))

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
(def subject-b {:subject/id "work:10.1/y" :subject/type :research-work})

(def obs-a
  (influence-observation
   [(signal {:content-hash "a"})]
   window-1 subject-a "mv-2026-09-01"))

;; A record co-measuring TWO keys in one record (attention alongside citation).
(def obs-a-two-keys
  (-> obs-a
      (assoc-in [:tallies :tally/views] 12)
      (assoc-in [:tallies :tally/policy-citation] 2)))

;; A record co-measuring a DIFFERENT pair (views + funding) in the same group.
(def obs-a-other-pair
  (-> obs-a
      (assoc-in [:tallies :tally/views] 7)
      (assoc-in [:tallies :tally/funding-usd] 5000)))

;; A single-key record produces NO pairs.
(def obs-a-single-key
  (assoc-in obs-a [:tallies :tally/views] 3))

;; Same subject, different window -> different identity group.
(def obs-a-other-window
  (-> (influence-observation
       [(signal {:observed-at 1700100100 :content-hash "c"})]
       window-2 subject-a "mv-2026-09-01")
      (assoc-in [:tallies :tally/views] 5)
      (assoc-in [:tallies :tally/funding-usd] 500)))

;; Same window, different subject -> different identity group.
(def obs-b
  (-> (influence-observation
       [(signal {:content-hash "d"})]
       window-1 subject-b "mv-2026-09-01")
      (assoc-in [:tallies :tally/views] 9)
      (assoc-in [:tallies :tally/funding-usd] 100)))

(defn accepted [result]
  (is (= :accepted (first result)))
  (second result))

(deftest single-record-with-three-keys-yields-three-presence-pairs
  (let [obs (accepted (cm/build-co-measurement-observation [obs-a-two-keys]))
        [gid group] (first (:groups obs))]
    (is (= 1 (:record-count group)))
    ;; obs-a's admitted signal already carries :tally/scholarly-citation, so
    ;; this record measures three keys -> three co-measured pairs.
    (is (= 3 (:pair-count group)))
    ;; Pairs are sorted presence facts, not relationships.
    (is (= [[:tally/policy-citation :tally/scholarly-citation]
            [:tally/policy-citation :tally/views]
            [:tally/scholarly-citation :tally/views]]
           (:pairs group)))
    ;; Each pair carries the record count that produced it — attribution, not strength.
    (is (= 3 (count (:pairs-per-record-count group))))))

(deftest single-key-record-produces-no-pairs
  (let [obs (accepted (cm/build-co-measurement-observation [obs-a]))
        group (second (first (:groups obs)))]
    (is (= 0 (:pair-count group)))
    (is (= 1 (:record-count group)))
    (is (= [] (:pairs group)))))

(deftest pairs-are-attributed-within-their-identity-only
  (let [obs (accepted (cm/build-co-measurement-observation
                       [obs-a-other-pair obs-a-other-window obs-b]))]
    ;; Three identity groups: (subject-a, window-1), (subject-a, window-2), (subject-b, window-1).
    (is (= 3 (:identities (:flags obs))))
    ;; Each group carries exactly the pair(s) its own records measured: one
    ;; pair per group here, and no group is empty of its own attribution.
    (doseq [[_ group] (:groups obs)]
      (is (= 3 (:pair-count group)))
      (is (= 1 (:record-count group)))
      (is (seq (:pairs-per-record-count group))))
    ;; The window-2 group is a distinct identity from the window-1 group.
    (is (= 3 (count (:groups obs))))))

(deftest union-across-records-in-one-group-counts-attribution-not-strength
  (let [obs (accepted (cm/build-co-measurement-observation
                       [obs-a-other-pair obs-a-other-pair]))
        group (second (first (:groups obs)))]
    ;; Same pairs carried by two records: two mentions each, still three
    ;; distinct pairs, no summing of values.
    (is (= 3 (:pair-count group)))
    (is (= 2 (:record-count group)))
    (is (every? #(= 2 %) (vals (:pairs-per-record-count group))))))

(deftest structural-refusals-are-hardwired-even-with-no-pairs
  (let [obs (accepted (cm/build-co-measurement-observation [obs-a]))]
    (is (true? (:correlation-computation-forbidden (:flags obs))))
    (is (true? (:causal-inference-forbidden (:flags obs))))
    (is (true? (:correlation-computation-forbidden obs)))
    (is (true? (:causal-inference-forbidden obs)))
    (is (nil? (:pair-strengths obs)))
    (is (true? (:pair-strengths-forbidden obs)))))

(deftest no-ranking-no-aggregate-no-claims-ever
  (let [obs (accepted (cm/build-co-measurement-observation
                       [obs-a-other-pair obs-b]))]
    (is (nil? (:ranking obs)))
    (is (true? (:ranking-forbidden obs)))
    (is (nil? (:aggregate obs)))
    (is (true? (:aggregation-forbidden obs)))
    (is (empty? (:claims obs)))
    (is (true? (:causal-claims-forbidden obs)))
    (is (= :partial (:coverage obs)))))

(deftest non-vector-history-is-rejected
  (is (= :rejected (first (cm/build-co-measurement-observation {:not "a vector"})))))

(deftest poisoned-history-is-rejected-not-cleaned
  (is (= :rejected
         (first (cm/build-co-measurement-observation
                 [obs-a (dissoc obs-a-two-keys :tallies)])))))

(deftest empty-history-is-flagged
  (let [obs (accepted (cm/build-co-measurement-observation []))]
    (is (true? (get-in obs [:flags :empty-history])))
    (is (nil? (:coverage-window obs)))))

(deftest record-values-are-never-consumed-into-the-pair-facts
  ;; Presence only: swapping the tally VALUES must not change the pairs.
  (let [revalued (assoc-in obs-a-other-pair [:tallies :tally/views] 999999)
        a (accepted (cm/build-co-measurement-observation [obs-a-other-pair]))
        b (accepted (cm/build-co-measurement-observation [revalued]))]
    (is (= (:pairs (second (first (:groups a))))
           (:pairs (second (first (:groups b))))))))

(deftest derived-record-is-byte-identical-across-runs
  (let [obs-1 (accepted (cm/build-co-measurement-observation
                         [obs-a-other-pair obs-b]))
        obs-2 (accepted (cm/build-co-measurement-observation
                         [obs-a-other-pair obs-b]))]
    (is (= (pr-str obs-1) (pr-str obs-2)))))

;; ---------------------------------------------------------------------------
;; Append-only refresh history
;; ---------------------------------------------------------------------------

(deftest refresh-is-append-only
  (let [o1 (accepted (cm/build-co-measurement-observation [obs-a]))
        o2 (accepted (cm/build-co-measurement-observation [obs-a-other-pair]))
        hist (cm/refresh (cm/refresh [] o1) o2)]
    (is (= [o1 o2] (cm/history-records hist)))
    (is (= o1 (first (cm/history-records hist))))
    ;; prior record is untouched
    (is (= 1 (:history-size (nth hist 0))))
    (is (= 2 (count hist)))))

;; ---------------------------------------------------------------------------
;; Hyakka proposal / readback
;; ---------------------------------------------------------------------------

(defn proposal-for [obs] (cm/hyakka-proposal obs))

(deftest proposal-carries-pairs-and-refusals
  (let [obs (accepted (cm/build-co-measurement-observation [obs-a-other-pair]))
        p (proposal-for obs)]
    (is (some? p))
    (is (= "co-measurement-observation/v1" (:proposal/contract p)))
    (is (= (:groups obs) (:proposal/groups p)))
    (is (true? (get-in p [:proposal/flags :correlation-computation-forbidden])))
    (is (true? (get-in p [:proposal/flags :causal-inference-forbidden])))
    (is (nil? (:proposal/pair-strengths p)))
    (is (true? (:proposal/pair-strengths-forbidden p)))))

(deftest empty-history-proposes-nothing
  (let [obs (accepted (cm/build-co-measurement-observation []))]
    (is (nil? (proposal-for obs)))))

(deftest dedupe-key-is-deterministic-and-sensitive
  (let [obs-1 (accepted (cm/build-co-measurement-observation [obs-a-other-pair]))
        obs-2 (accepted (cm/build-co-measurement-observation [obs-a-other-pair]))
        obs-3 (accepted (cm/build-co-measurement-observation [obs-a-two-keys]))]
    (is (= (cm/dedupe-key obs-1) (cm/dedupe-key obs-2)))
    (is (not= (cm/dedupe-key obs-1) (cm/dedupe-key obs-3)))
    ;; groups are a sorted map keyed by identity — history order must not leak
    (is (= (cm/dedupe-key (accepted (cm/build-co-measurement-observation
                                     [obs-a-other-pair obs-b])))
           (cm/dedupe-key (accepted (cm/build-co-measurement-observation
                                     [obs-b obs-a-other-pair])))))))

(deftest readback-accepts-untouched-proposal
  (let [obs (accepted (cm/build-co-measurement-observation [obs-a-other-pair]))
        p (proposal-for obs)]
    (is (cm/hyakka-readback-accept? p p))
    (is (cm/hyakka-readback-accept? p (assoc p :proposal/extra-field "irrelevant")))))

(deftest readback-refuses-a-stripped-refusal
  (let [obs (accepted (cm/build-co-measurement-observation [obs-a-other-pair]))
        p (proposal-for obs)
        stripped (-> p
                     (update :proposal/flags dissoc :correlation-computation-forbidden)
                     (assoc :proposal/flags
                            (dissoc (:proposal/flags p) :correlation-computation-forbidden)))]
    (is (not (cm/hyakka-readback-accept? p stripped)))))

(deftest readback-refuses-strengths-injected-by-the-wiki
  (let [obs (accepted (cm/build-co-measurement-observation [obs-a-other-pair]))
        p (proposal-for obs)
        tampered (assoc p :proposal/pair-strengths {[:tally/funding-usd :tally/views] 0.87})]
    (is (not (cm/hyakka-readback-accept? p tampered)))))

(deftest readback-refuses-different-dedupe-key
  (let [obs-1 (accepted (cm/build-co-measurement-observation [obs-a-other-pair]))
        obs-2 (accepted (cm/build-co-measurement-observation [obs-b]))
        p1 (proposal-for obs-1)
        p2 (proposal-for obs-2)]
    (is (not (cm/hyakka-readback-accept? p1 p2)))))

(deftest readback-refuses-claims-injected-by-the-wiki
  (let [obs (accepted (cm/build-co-measurement-observation [obs-a-other-pair]))
        p (proposal-for obs)
        tampered (assoc p :proposal/claims ["citation predicts funding"])]
    (is (not (cm/hyakka-readback-accept? p tampered)))))
