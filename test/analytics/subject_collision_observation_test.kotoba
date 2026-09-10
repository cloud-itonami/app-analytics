(ns analytics.subject-collision-observation-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [analytics.subject-collision-observation :as sco]))

;; ---------------------------------------------------------------------------
;; Fixture builders — conformant influence-observation/v1 records
;; ---------------------------------------------------------------------------

(defn- obs
  ([] (obs {}))
  ([{:keys [subject from to mv tallies strip]
     :or {subject "researchers-a"
          from 1000 to 2000
          mv "mv-1"
          tallies {:tally/scholarly-citation 3}
          strip #{}}}]
   (cond-> {:contract "influence-observation"
            :version "v1"
            :method-version mv
            :subject subject
            :window {:from from :to to}
            :tallies tallies
            :admitted-count 3
            :flags {:missing-is-unmeasured true :coverage :partial}
            :ranking nil
            :ranking-forbidden true
            :causal-claims-forbidden true
            :claims []}
     (contains? strip :tallies)          (dissoc :tallies)
     (contains? strip :flags)            (dissoc :flags)
     (contains? strip :ranking-refusals) (dissoc :ranking :ranking-forbidden))))

(deftest build-refusals
  ;; non-vector history
  (is (= [:rejected :history-not-vector]
         (sco/build-subject-collision-observation (list (obs)))))
  ;; non-conformant record poisons the whole audit
  (is (= [:rejected :non-conformant-observation-in-history]
         (sco/build-subject-collision-observation [(obs) {:contract "x"}])))
  (is (= [:rejected :non-conformant-observation-in-history]
         (sco/build-subject-collision-observation [(assoc (obs) :ranking [{:r 1}])])))
  (is (= [:rejected :non-conformant-observation-in-history]
         (sco/build-subject-collision-observation [(assoc (obs) :ranking-forbidden false)]))))

(deftest collision-detection-verbatim-preservation
  ;; whitespace-edge + case variants collide under the comparison key...
  (let [[_ audit] (sco/build-subject-collision-observation
                   [(obs {:subject " researchers-a "})
                    (obs {:subject "Researchers-A"})
                    (obs {:subject "researchers-a"})])
        groups (:groups audit)
        flagged (filter :collision-candidate? (vals groups))]
    ;; exactly one comparison key, three distinct VERBATIM subjects
    (is (= 1 (count groups)))
    (is (true? (:collision-candidate? (first (vals groups)))))
    (is (= #{"Researchers-A" " researchers-a " "researchers-a"}
           (set (:subjects (first (vals groups))))))
    (is (= 3 (:variants (first (vals groups)))))
    (is (= 3 (:record-count (first (vals groups)))))
    (is (= 1 (get-in audit [:flags :collision-groups])))
    (is (= 3 (get-in audit [:flags :collision-records])))
    ;; verbatim subjects travel into the derived record untouched
    (is (= #{"Researchers-A" " researchers-a " "researchers-a"}
           (set (:subjects (first (vals groups))))))))

(deftest no-collision-when-distinct
  (let [[_ audit] (sco/build-subject-collision-observation
                   [(obs {:subject "alpha"})
                    (obs {:subject "beta"})])]
    (is (= 0 (get-in audit [:flags :collision-groups])))
    (is (= 2 (get-in audit [:flags :identities])))
    (is (every? (comp false? :collision-candidate?) (vals (:groups audit))))
    ;; distinct subjects are still reported, not dropped
    (is (= #{["alpha"] ["beta"]} (set (map :subjects (vals (:groups audit))))))))

(deftest normalization-is-exactly-two
  ;; case + trim collide; other differences do NOT (no fuzzy matching)
  (let [[_ audit] (sco/build-subject-collision-observation
                   [(obs {:subject "Researcher B"})
                    (obs {:subject "researcher-b"})])
        groups (:groups audit)]
    ;; "Researcher B" (space) vs "researcher-b" (hyphen) differ beyond the two
    ;; normalizations → distinct keys, no collision
    (is (= 2 (count groups)))
    (is (= 0 (get-in audit [:flags :collision-groups]))))
  ;; repeated identical subjects are one group, not a collision
  (let [[_ audit] (sco/build-subject-collision-observation
                   [(obs {:subject "alpha"}) (obs {:subject "alpha"})])]
    (is (= 1 (count (:groups audit))))
    (is (= 0 (get-in audit [:flags :collision-groups])))
    (is (= 1 (:variants (first (vals (:groups audit))))))))

(deftest hardwired-refusals-hold-even-without-collisions
  (let [[_ audit] (sco/build-subject-collision-observation [(obs)])]
    (is (true? (:identity-resolution-forbidden audit)))
    (is (true? (:merge-forbidden audit)))
    (is (true? (get-in audit [:flags :identity-resolution-forbidden])))
    (is (true? (get-in audit [:flags :merge-forbidden])))
    (is (nil? (:resolution audit)))
    (is (nil? (:aggregate audit)))
    (is (true? (:aggregation-forbidden audit)))
    (is (nil? (:ranking audit)))
    (is (true? (:ranking-forbidden audit)))
    (is (true? (:causal-claims-forbidden audit)))
    (is (= [] (:claims audit)))
    (is (= :partial (:coverage audit)))))

(deftest byte-identical-determinism
  (let [history [(obs {:subject " A "}) (obs {:subject "a"}) (obs {:subject "Beta"})]
        r1 (sco/build-subject-collision-observation history)
        r2 (sco/build-subject-collision-observation history)]
    (is (= (pr-str r1) (pr-str r2)))))

(deftest refresh-is-append-only
  (let [[_ a1] (sco/build-subject-collision-observation [(obs)])
        [_ a2] (sco/build-subject-collision-observation [(obs) (obs {:subject " A "})])
        h (sco/refresh (sco/refresh [] a1) a2)]
    (is (= [a1 a2] (sco/history-records h)))
    (is (= a1 (first (sco/history-records h))))))

(deftest hyakka-proposal-and-readback
  (let [[_ audit] (sco/build-subject-collision-observation
                   [(obs {:subject " researchers-a "}) (obs {:subject "Researchers-A"})])
        proposal (sco/hyakka-proposal audit)]
    (is (some? proposal))
    (is (= :subject-collision (:proposal/type proposal)))
    (is (some? (:proposal/dedupe-key proposal)))
    (is (true? (:proposal/identity-resolution-forbidden proposal)))
    (is (true? (:proposal/merge-forbidden proposal)))
    (is (nil? (:proposal/resolution proposal)))
    ;; happy readback
    (is (true? (sco/hyakka-readback-accept? proposal proposal)))
    ;; tampering: refusals stripped / fields mutated → refuse
    (is (false? (sco/hyakka-readback-accept? proposal (assoc proposal :proposal/resolution "merged"))))
    (is (false? (sco/hyakka-readback-accept? proposal (assoc proposal :proposal/identity-resolution-forbidden false))))
    (is (false? (sco/hyakka-readback-accept? proposal (update proposal :proposal/flags dissoc :merge-forbidden))))
    (is (false? (sco/hyakka-readback-accept? proposal (assoc proposal :proposal/ranking [{:r 1}]))))
    (is (false? (sco/hyakka-readback-accept? proposal (assoc proposal :proposal/claims [{:c 1}]))))
    (is (false? (sco/hyakka-readback-accept? proposal (assoc proposal :proposal/aggregate {:total 3}))))
    (is (false? (sco/hyakka-readback-accept? proposal (assoc proposal :proposal/coverage :complete))))
    (is (false? (sco/hyakka-readback-accept? proposal (assoc proposal :proposal/dedupe-key "other"))))
    (is (false? (sco/hyakka-readback-accept? proposal (update-in proposal [:proposal/groups] assoc "injected" {}))))))

(deftest empty-history-proposes-nothing
  ;; empty history is a valid (accepted) audit but proposes nothing to Hyakka
  (let [[_ audit] (sco/build-subject-collision-observation [])]
    (is (true? (get-in audit [:flags :empty-history])))
    (is (nil? (sco/hyakka-proposal audit)))))

(defn ^:export run []
  (run-tests 'analytics.subject-collision-observation-test))
