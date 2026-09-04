(ns analytics.self-consistency-observation-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [analytics.self-consistency-observation :as sco]))

;; ---------------------------------------------------------------------------
;; Fixture builders — conformant influence-observation/v1 records
;; ---------------------------------------------------------------------------

(defn- obs
  ([] (obs {}))
  ([{:keys [subject from to mv tallies admitted-count extra-flags drop-flag
            strip]
     :or {subject {:name " researchers-a "}
          from 1000 to 2000
          mv "mv-1"
          tallies {:tally/scholarly-citation 3}
          admitted-count 3
          extra-flags {}
          drop-flag #{}
          strip #{}}}]
   (cond-> {:contract "influence-observation"
            :version "v1"
            :method-version mv
            :subject subject
            :window {:from from :to to}
            :tallies tallies
            :admitted-count admitted-count
            :flags (merge {:missing-is-unmeasured true
                           :excluded-signals-present false
                           :retraction-present false
                           :correction-present false
                           :single-source-dependency false
                           :duplicate-content-hash false
                           :coverage :partial}
                          extra-flags)
            :ranking nil
            :ranking-forbidden true
            :causal-claims-forbidden true
            :claims []}
     (contains? drop-flag :retraction-present) (update :flags dissoc :retraction-present)
     (contains? drop-flag :correction-present) (update :flags dissoc :correction-present)
     (contains? strip :admitted-count)         (dissoc :admitted-count))))

;; ---------------------------------------------------------------------------
;; Refusals — same admission shapes as the sibling contracts
;; ---------------------------------------------------------------------------

(deftest build-refusals
  ;; non-vector history
  (is (= [:rejected :history-not-vector]
         (sco/build-self-consistency-observation "sc-mv-1" (list (obs)))))
  ;; empty history measures nothing — refused, not reported as all-consistent
  (is (= [:rejected :empty-history]
         (sco/build-self-consistency-observation "sc-mv-1" [])))
  ;; missing audit method-version
  (is (= [:rejected :missing-method-version]
         (sco/build-self-consistency-observation "" [(obs)])))
  (is (= [:rejected :missing-method-version]
         (sco/build-self-consistency-observation nil [(obs)])))
  ;; non-conformant record poisons the whole audit
  (is (= [:rejected :non-conformant-observation-in-history]
         (sco/build-self-consistency-observation "sc-mv-1" [(obs) {:contract "x"}])))
  ;; identity mixing: same shape, different window/method/subject → refused,
  ;; not merged
  (is (= [:rejected :identity-mismatch]
         (sco/build-self-consistency-observation
          "sc-mv-1"
          [(obs {:from 1000 :to 2000})
           (obs {:from 1000 :to 2001})])))
  (is (= [:rejected :identity-mismatch]
         (sco/build-self-consistency-observation
          "sc-mv-1"
          [(obs {:mv "mv-1"}) (obs {:mv "mv-2"})])))
  (is (= [:rejected :identity-mismatch]
         (sco/build-self-consistency-observation
          "sc-mv-1"
          [(obs {:subject {:name "a"}}) (obs {:subject {:name "b"}})])))
  ;; a PRESENT field with a malformed shape poisons the audit
  (is (= [:rejected :non-conformant-observation-in-history]
         (sco/build-self-consistency-observation "sc-mv-1" [(assoc (obs) :tallies 5)])))
  (is (= [:rejected :non-conformant-observation-in-history]
         (sco/build-self-consistency-observation "sc-mv-1" [(assoc (obs) :flags 5)]))))

;; ---------------------------------------------------------------------------
;; Flag-vs-tally pairs — presence-only, additive, never repaired
;; ---------------------------------------------------------------------------

(deftest flag-verdicts-presence-only
  ;; retraction flag true, retraction tally absent → mismatch
  (let [[_ a] (sco/build-self-consistency-observation
               "sc-mv-1"
               [(obs {:extra-flags {:retraction-present true}})])
        v     (->> (get-in a [:findings 0 :flag-verdicts])
                   (filter #(= :retraction-present (:flag-key %)))
                   first)]
    (is (= :retraction-present (:flag-key v)))
    (is (= :tally/retraction (:tally-key v)))
    (is (true? (:flag-value v)))
    (is (false? (:tally-present v)))
    (is (false? (:agrees v)))
    (is (true? (get-in a [:flags :mismatch-present])))
    (is (= 1 (get-in a [:flags :records-with-mismatch]))))
  ;; retraction flag true AND tally present → agrees
  (let [[_ a] (sco/build-self-consistency-observation
               "sc-mv-1"
               [(obs {:tallies {:tally/scholarly-citation 2 :tally/retraction 1}
                      :extra-flags {:retraction-present true}})])
        v     (->> (get-in a [:findings 0 :flag-verdicts])
                   (filter #(= :retraction-present (:flag-key %)))
                   first)]
    (is (true? (:agrees v)))
    (is (false? (get-in a [:flags :mismatch-present]))))
  ;; flag false, tally absent → agrees
  (let [[_ a] (sco/build-self-consistency-observation "sc-mv-1" [(obs)])
        v     (->> (get-in a [:findings 0 :flag-verdicts])
                   (filter #(= :retraction-present (:flag-key %)))
                   first)]
    (is (true? (:agrees v))))
  ;; flag absent from the record → :side-absent, a finding — never a shared
  ;; default value
  (let [[_ a] (sco/build-self-consistency-observation
               "sc-mv-1"
               [(obs {:drop-flag #{:retraction-present}})])
        v     (->> (get-in a [:findings 0 :flag-verdicts])
                   (filter #(= :retraction-present (:flag-key %)))
                   first)]
    (is (true? (:flag-side-absent v)))
    (is (nil? (:flag-value v)))
    (is (= :side-absent (:agrees v)))
    (is (true? (get-in a [:flags :side-absent-present])))
    ;; side-absent is NOT a mismatch — absence is its own finding
    (is (false? (:mismatch-present (get-in a [:findings 0])))))
  ;; correction pair is audited too
  (let [[_ a] (sco/build-self-consistency-observation
               "sc-mv-1"
               [(obs {:extra-flags {:correction-present true}})])
        v     (->> (get-in a [:findings 0 :flag-verdicts])
                   (filter #(= :correction-present (:flag-key %)))
                   first)]
    (is (= :correction-present (:flag-key v)))
    (is (= :tally/correction (:tally-key v)))
    (is (false? (:agrees v)))))

;; ---------------------------------------------------------------------------
;; Count agreement — admitted-count vs additive tally sum
;; ---------------------------------------------------------------------------

(deftest count-verdict-additive
  ;; 3 + 0 = 3 = admitted-count → agrees
  (let [[_ a] (sco/build-self-consistency-observation "sc-mv-1" [(obs)])
        c     (get-in a [:findings 0 :count-verdict])]
    (is (= 3 (:admitted-count c)))
    (is (= 3 (:tally-sum c)))
    (is (true? (:agrees c))))
  ;; count disagrees with tally sum → flagged, both sides travel, nothing
  ;; reweighted
  (let [[_ a] (sco/build-self-consistency-observation
               "sc-mv-1"
               [(obs {:tallies {:tally/scholarly-citation 2} :admitted-count 5})])
        c     (get-in a [:findings 0 :count-verdict])]
    (is (= 5 (:admitted-count c)))
    (is (= 2 (:tally-sum c)))
    (is (false? (:agrees c)))
    (is (true? (get-in a [:flags :mismatch-present]))))
  ;; admitted-count absent → :side-absent, a finding — never folded into the
  ;; tally sum
  (let [[_ a] (sco/build-self-consistency-observation
               "sc-mv-1"
               [(obs {:strip #{:admitted-count}})])
        c     (get-in a [:findings 0 :count-verdict])]
    (is (true? (:admitted-count-side-absent c)))
    (is (= :side-absent (:agrees c)))
    (is (= 3 (:tally-sum c)))
    (is (true? (get-in a [:flags :side-absent-present])))
    (is (false? (:mismatch-present (get-in a [:findings 0]))))))

;; ---------------------------------------------------------------------------
;; Unaudited flag keys — enumerated, never guessed at
;; ---------------------------------------------------------------------------

(deftest unaudited-flags-enumerated-not-evaluated
  (let [[_ a] (sco/build-self-consistency-observation "sc-mv-1" [(obs)])
        f     (get-in a [:findings 0])]
    ;; the known-but-unverifiable flags are enumerated verbatim
    (is (= [:coverage :duplicate-content-hash :excluded-signals-present
            :missing-is-unmeasured :single-source-dependency]
           (:unaudited-flag-keys f))))
  ;; an unknown flag key in a record is enumerated as unaudited too — never
  ;; silently skipped and never assigned a verdict
  (let [[_ a] (sco/build-self-consistency-observation
               "sc-mv-1"
               [(obs {:extra-flags {:some-novel-flag true}})])
        f     (get-in a [:findings 0])]
    (is (contains? (set (:unaudited-flag-keys f)) :some-novel-flag))))

;; ---------------------------------------------------------------------------
;; Structural refusals stay hardwired; record ordering; additivity
;; ---------------------------------------------------------------------------

(deftest structural-boundaries
  (let [[_ a] (sco/build-self-consistency-observation "sc-mv-1" [(obs)])]
    (is (true? (:repair-forbidden a)))
    (is (true? (get-in a [:flags :repair-forbidden])))
    (is (nil? (:ranking a)))
    (is (true? (:ranking-forbidden a)))
    (is (true? (:causal-claims-forbidden a)))
    (is (empty? (:claims a)))
    (is (= :partial (get-in a [:flags :coverage]))))
  ;; multiple records: findings carry their original index, counts additive
  (let [history [(obs)
                 (obs {:extra-flags {:retraction-present true}})
                 (obs {:tallies {:tally/scholarly-citation 1} :admitted-count 1})]
        [_ a]  (sco/build-self-consistency-observation "sc-mv-1" history)]
    (is (= 3 (:history-size a)))
    (is (= [0 1 2] (mapv :record-index (:findings a))))
    (is (= 1 (get-in a [:flags :records-with-mismatch])))))

;; ---------------------------------------------------------------------------
;; Determinism — byte-identical pr-str across runs
;; ---------------------------------------------------------------------------

(deftest deterministic-byte-identical
  (let [history [(obs {:extra-flags {:retraction-present true}})
                 (obs {:tallies {:tally/scholarly-citation 2 :tally/correction 1}
                      :admitted-count 3
                      :extra-flags {:correction-present true}})]
        p1 (pr-str (sco/build-self-consistency-observation "sc-mv-1" history))
        p2 (pr-str (sco/build-self-consistency-observation "sc-mv-1" history))]
    (is (= p1 p2))))

;; ---------------------------------------------------------------------------
;; Refresh history — append-only, immutable priors
;; ---------------------------------------------------------------------------

(deftest refresh-append-only
  (let [[_ a1] (sco/build-self-consistency-observation "sc-mv-1" [(obs)])
        [_ a2] (sco/build-self-consistency-observation "sc-mv-2" [(obs)])
        h0     (sco/history-records (sco/refresh [] a1))
        h1     (sco/history-records (sco/refresh h0 a2))]
    (is (= 1 (count h0)))
    (is (= 2 (count h1)))
    (is (= a1 (nth h1 0)))   ; prior record untouched
    (is (= a2 (nth h1 1)))))

;; ---------------------------------------------------------------------------
;; Hyakka proposal / readback
;; ---------------------------------------------------------------------------

(deftest hyakka-proposal-and-readback
  (let [[_ a] (sco/build-self-consistency-observation
               "sc-mv-1"
               [(obs {:extra-flags {:retraction-present true}})])
        p     (sco/hyakka-proposal a)]
    ;; proposal exists for a non-empty audit
    (is (some? p))
    (is (= "self-consistency-observation/v1" (:proposal/contract p)))
    (is (some? (:proposal/dedupe-key p)))
    (is (= :partial (:proposal/coverage p)))
    (is (true? (:proposal/repair-forbidden p)))
    (is (nil? (:proposal/ranking p)))
    (is (true? (:proposal/ranking-forbidden p)))
    (is (true? (:proposal/causal-claims-forbidden p)))
    (is (empty? (:proposal/claims p)))
    ;; empty audit proposes nothing
    (is (nil? (:proposal/dedupe-key nil)))
    ;; honest readback accepted
    (is (true? (sco/hyakka-readback-accept? p p)))
    ;; tampering refused: stripped refusal flags
    (is (false? (sco/hyakka-readback-accept? p (dissoc p :proposal/ranking-forbidden))))
    (is (false? (sco/hyakka-readback-accept? p (dissoc p :proposal/causal-claims-forbidden))))
    (is (false? (sco/hyakka-readback-accept? p (dissoc p :proposal/repair-forbidden))))
    ;; injected ranking or claims
    (is (false? (sco/hyakka-readback-accept? p (assoc p :proposal/ranking [{:r 1}]))))
    (is (false? (sco/hyakka-readback-accept? p (assoc p :proposal/claims [{:claim "x"}]))))
    ;; injected repaired records — the exact thing this contract forbids
    (is (false? (sco/hyakka-readback-accept? p (assoc p :proposal/repaired-records [1]))))
    ;; stripped mismatch flag
    (is (false? (sco/hyakka-readback-accept? p
                                             (update p :proposal/flags dissoc :mismatch-present))))
    ;; coverage upgraded to a completeness claim → refuse
    (is (false? (sco/hyakka-readback-accept? p (assoc p :proposal/coverage :complete))))
    ;; altered findings → refuse
    (is (false? (sco/hyakka-readback-accept? p (update-in p [:proposal/findings 0 :count-verdict :tally-sum] + 1))))
    ;; dedupe-key mismatch → refuse
    (is (false? (sco/hyakka-readback-accept? p (assoc p :proposal/dedupe-key "other"))))))

(deftest complete-history-consistent-has-no-mismatch
  (let [[_ a] (sco/build-self-consistency-observation "sc-mv-1" [(obs) (obs)])]
    (is (false? (get-in a [:flags :mismatch-present])))
    (is (= 0 (get-in a [:flags :records-with-mismatch])))
    (is (false? (get-in a [:flags :side-absent-present])))
    (is (= :partial (get-in a [:flags :coverage])))))   ; still partial — history ≠ world

(defn ^:export run []
  (run-tests 'analytics.self-consistency-observation-test))
