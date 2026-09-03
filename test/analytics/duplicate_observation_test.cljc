(ns analytics.duplicate-observation-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [analytics.duplicate-observation :as dobs]))

;; ---------------------------------------------------------------------------
;; Fixture builders — conformant influence-observation/v1 records
;; ---------------------------------------------------------------------------

(defn- obs
  ([] (obs {}))
  ([{:keys [subject from to mv tallies admitted-count flags strip]
     :or {subject {:name " researchers-a "}
          from 1000 to 2000
          mv "mv-1"
          tallies {:tally/scholarly-citation 3}
          admitted-count 3
          flags {:missing-is-unmeasured true :coverage :partial}
          strip #{}}}]
   (cond-> {:contract "influence-observation"
            :version "v1"
            :method-version mv
            :subject subject
            :window {:from from :to to}
            :tallies tallies
            :admitted-count admitted-count
            :flags flags
            :ranking nil
            :ranking-forbidden true
            :causal-claims-forbidden true
            :claims []}
     (contains? strip :tallies)        (dissoc :tallies)
     (contains? strip :admitted-count) (dissoc :admitted-count)
     (contains? strip :flags)          (dissoc :flags)
     (contains? strip :ranking-refusals) (dissoc :ranking :ranking-forbidden))))

(deftest build-refusals
  ;; non-vector history
  (is (= [:rejected :history-not-vector]
         (dobs/build-duplicate-observation "audit-mv-1" (list (obs)))))
  ;; empty history measures nothing — refused, not reported as zero duplicates
  (is (= [:rejected :empty-history]
         (dobs/build-duplicate-observation "audit-mv-1" [])))
  ;; missing audit method-version
  (is (= [:rejected :missing-method-version]
         (dobs/build-duplicate-observation "" [(obs)])))
  (is (= [:rejected :missing-method-version]
         (dobs/build-duplicate-observation nil [(obs)])))
  ;; non-conformant record poisons the whole audit
  (is (= [:rejected :non-conformant-observation-in-history]
         (dobs/build-duplicate-observation "audit-mv-1" [(obs) {:contract "x"}])))
  ;; identity mixing: same shape, different window/method/subject → refused,
  ;; not merged
  (is (= [:rejected :identity-mismatch]
         (dobs/build-duplicate-observation
          "audit-mv-1"
          [(obs {:from 1000 :to 2000})
           (obs {:from 1000 :to 2001})])))
  (is (= [:rejected :identity-mismatch]
         (dobs/build-duplicate-observation
          "audit-mv-1"
          [(obs {:mv "mv-1"}) (obs {:mv "mv-2"})])))
  (is (= [:rejected :identity-mismatch]
         (dobs/build-duplicate-observation
          "audit-mv-1"
          [(obs {:subject {:name "a"}}) (obs {:subject {:name "b"}})])))
  ;; a PRESENT field with a malformed value poisons the audit
  (is (= [:rejected :non-conformant-observation-in-history]
         (dobs/build-duplicate-observation "audit-mv-1" [(assoc (obs) :tallies 5)])))
  (is (= [:rejected :non-conformant-observation-in-history]
         (dobs/build-duplicate-observation "audit-mv-1" [(assoc (obs) :ranking [{:r 1}])])))
  (is (= [:rejected :non-conformant-observation-in-history]
         (dobs/build-duplicate-observation "audit-mv-1" [(assoc (obs) :ranking-forbidden false)])))
  (is (= [:rejected :non-conformant-observation-in-history]
         (dobs/build-duplicate-observation "audit-mv-1" [(assoc (obs) :window "w")]))))

(deftest duplicates-counted-not-removed
  ;; 4 records, 2 value-identical pairs → 1 duplicate group of 2 … plus one
  ;; singleton; nothing is removed and the history size is preserved
  (let [history [(obs)                                  ; fingerprint A
                 (obs)                                  ; fingerprint A (duplicate)
                 (obs {:tallies {:tally/scholarly-citation 7}
                      :admitted-count 7})
                 (obs {:tallies {:tally/scholarly-citation 7}
                      :admitted-count 7})]              ; fingerprint B (duplicate)
        [_ audit] (dobs/build-duplicate-observation "audit-mv-1" history)
        dup (:duplication audit)
        fl   (:flags audit)]
    ;; additive counts: duplicated-records counts BOTH members, never one
    (is (= 2 (count (filter #(> (long (:record-count %)) 1) (:groups dup)))))
    (is (= 4 (:duplicated-records fl)))
    (is (= 0 (:singleton-records fl)))
    (is (= 4 (:history-size audit)))                    ; nothing removed
    (is (= 2 (:distinct-fingerprints dup)))
    (is (true? (:duplicate-groups-present fl)))
    ;; each group's indices identify its members without dropping any
    (is (every? #(= (long (:record-count %)) (count (:record-indices %)))
                (:groups dup)))))

(deftest absence-is-not-zero
  ;; a record MISSING :admitted-count does not match a record whose
  ;; :admitted-count is 0 — absence gets its own marker
  (let [history [(obs {:admitted-count 0})
                 (obs {:strip #{:admitted-count}})
                 (obs {:strip #{:admitted-count}})]
        [_ audit] (dobs/build-duplicate-observation "audit-mv-1" history)
        groups (:groups (:duplication audit))
        fl (:flags audit)]
    ;; the two field-absent records group together; the zero-count record is
    ;; a distinct singleton
    (is (= 2 (:distinct-fingerprints (:duplication audit))))
    (is (= 2 (:duplicated-records fl)))
    (is (= 1 (:singleton-records fl)))
    (is (some (fn [g] (and (> (long (:record-count g)) 1)
                           (str/includes? (:fingerprint g) ":field-absent")))
              groups))))

(deftest different-flags-are-not-duplicates
  ;; different :flags values project differently — a record whose flags were
  ;; altered is NOT silently identical to its source
  (let [history [(obs)
                 (obs {:flags {:missing-is-unmeasured true :coverage :complete}})]
        [_ audit] (dobs/build-duplicate-observation "audit-mv-1" history)]
    (is (= 2 (:distinct-fingerprints (:duplication audit))))
    (is (false? (get-in audit [:flags :duplicate-groups-present])))
    (is (= 0 (:duplicated-records (:flags audit))))
    (is (= 2 (:singleton-records (:flags audit))))))

(deftest values-consumed-only-on-projection
  ;; re-valuing a tally CHANGES grouping (this audit compares projections) —
  ;; but only :tallies/:admitted-count/:flags matter; a changed :claims value
  ;; on a projected field leaves the grouping byte-identical
  (let [run (fn [tally]
              (dobs/build-duplicate-observation
               "audit-mv-1"
               [(obs {:tallies {:tally/scholarly-citation tally}})
                (obs {:tallies {:tally/scholarly-citation tally}
                      :claims []})]))]
    ;; same projection value → same groups even though record shapes differ
    (is (= (pr-str (run 3)) (pr-str (run 3))))
    ;; different projection value → different fingerprint
    (is (not= (pr-str (run 3)) (pr-str (run 5))))))

(deftest determinism
  (let [history [(obs) (obs) (obs {:strip #{:tallies}})]
        run (fn [] (pr-str (dobs/build-duplicate-observation "audit-mv-1" history)))]
    (is (= (run) (run)))))

(deftest refresh-append-only
  (let [[_ a1] (dobs/build-duplicate-observation "audit-mv-1" [(obs)])
        [_ a2] (dobs/build-duplicate-observation "audit-mv-1" [(obs) (obs)])
        h1     (dobs/refresh [] a1)
        h2     (dobs/refresh h1 a2)]
    (is (= [a1] h1))
    (is (= [a1 a2] h2))
    (is (= a1 (first h2)))                     ; prior record untouched
    (is (= [a1 a2] (dobs/history-records h2)))))

(deftest hyakka-proposal-and-readback
  (let [[_ audit] (dobs/build-duplicate-observation "audit-mv-1" [(obs) (obs)])
        proposal  (dobs/hyakka-proposal audit)]
    ;; empty audit proposes nothing
    (is (nil? (dobs/hyakka-proposal {:history-size 0})))
    (is (some? (:proposal/dedupe-key proposal)))
    ;; dedupe-key is deterministic
    (is (= (:proposal/dedupe-key proposal) (dobs/dedupe-key audit)))
    ;; honest readback accepted
    (is (true? (dobs/hyakka-readback-accept? proposal proposal)))
    ;; tampering refused: stripped refusal flags
    (is (false? (dobs/hyakka-readback-accept? proposal (dissoc proposal :proposal/ranking-forbidden))))
    (is (false? (dobs/hyakka-readback-accept? proposal (dissoc proposal :proposal/causal-claims-forbidden))))
    (is (false? (dobs/hyakka-readback-accept? proposal (dissoc proposal :proposal/destructive-dedupe-forbidden))))
    (is (false? (dobs/hyakka-readback-accept? proposal (dissoc proposal :proposal/suggestion-only))))
    ;; injected ranking or claims
    (is (false? (dobs/hyakka-readback-accept? proposal (assoc proposal :proposal/ranking [{:r 1}]))))
    (is (false? (dobs/hyakka-readback-accept? proposal (assoc proposal :proposal/claims [{:claim "x"}]))))
    ;; coverage upgraded to a completeness claim → refuse
    (is (false? (dobs/hyakka-readback-accept? proposal (assoc proposal :proposal/coverage :complete))))
    ;; altered duplication numbers → refuse
    (is (false? (dobs/hyakka-readback-accept?
                 proposal
                 (update-in proposal [:proposal/flags :duplicated-records] (constantly 0)))))
    ;; a destructive-dedupe inversion (records marked removed) → refuse
    (is (false? (dobs/hyakka-readback-accept?
                 proposal
                 (assoc-in proposal [:proposal/flags :destructive-dedupe-forbidden] false))))
    ;; dedupe-key mismatch → refuse
    (is (false? (dobs/hyakka-readback-accept? proposal (assoc proposal :proposal/dedupe-key "other"))))))

(deftest no-duplicate-history-is-still-partial
  (let [[_ audit] (dobs/build-duplicate-observation "audit-mv-1" [(obs) (obs {:tallies {}})])]
    (is (false? (get-in audit [:flags :duplicate-groups-present])))
    (is (= 2 (:singleton-records (:flags audit))))
    (is (= :partial (get-in audit [:flags :coverage])))   ; still partial — history ≠ world
    (is (true? (get-in audit [:flags :destructive-dedupe-forbidden])))))

(defn ^:export run []
  (run-tests 'analytics.duplicate-observation-test))
