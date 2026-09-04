(ns analytics.missingness-observation-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [analytics.missingness-observation :as mo]))

;; ---------------------------------------------------------------------------
;; Fixture builders — conformant influence-observation/v1 records
;; ---------------------------------------------------------------------------

(defn- obs
  ([] (obs {}))
  ([{:keys [subject from to mv tallies strip]
     :or {subject {:name " researchers-a "}
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
     (contains? strip :admitted-count)   (dissoc :admitted-count)
     (contains? strip :flags)            (dissoc :flags)
     (contains? strip :ranking-refusals) (dissoc :ranking :ranking-forbidden))))

(deftest build-refusals
  ;; non-vector history
  (is (= [:rejected :history-not-vector]
         (mo/build-missingness-observation "audit-mv-1" (list (obs)))))
  ;; empty history measures nothing — refused, not reported as all-fields-absent
  (is (= [:rejected :empty-history]
         (mo/build-missingness-observation "audit-mv-1" [])))
  ;; missing audit method-version
  (is (= [:rejected :missing-method-version]
         (mo/build-missingness-observation "" [(obs)])))
  (is (= [:rejected :missing-method-version]
         (mo/build-missingness-observation nil [(obs)])))
  ;; non-conformant record poisons the whole audit
  (is (= [:rejected :non-conformant-observation-in-history]
         (mo/build-missingness-observation "audit-mv-1" [(obs) {:contract "x"}])))
  ;; identity mixing: same shape, different window/method/subject → refused,
  ;; not merged
  (is (= [:rejected :identity-mismatch]
         (mo/build-missingness-observation
          "audit-mv-1"
          [(obs {:from 1000 :to 2000})
           (obs {:from 1000 :to 2001})])))
  (is (= [:rejected :identity-mismatch]
         (mo/build-missingness-observation
          "audit-mv-1"
          [(obs {:mv "mv-1"}) (obs {:mv "mv-2"})])))
  (is (= [:rejected :identity-mismatch]
         (mo/build-missingness-observation
          "audit-mv-1"
          [(obs {:subject {:name "a"}}) (obs {:subject {:name "b"}})])))
  ;; a PRESENT field with a malformed value poisons the audit
  ;; (absence is a finding; wrongness is a refusal)
  (is (= [:rejected :non-conformant-observation-in-history]
         (mo/build-missingness-observation "audit-mv-1" [(assoc (obs) :tallies 5)])))
  (is (= [:rejected :non-conformant-observation-in-history]
         (mo/build-missingness-observation "audit-mv-1" [(assoc (obs) :ranking [{:r 1}])])))
  (is (= [:rejected :non-conformant-observation-in-history]
         (mo/build-missingness-observation "audit-mv-1" [(assoc (obs) :ranking-forbidden false)])))
  (is (= [:rejected :non-conformant-observation-in-history]
         (mo/build-missingness-observation "audit-mv-1" [(assoc (obs) :window "w")]))))

(deftest derive-missingness-additive-and-presence-only
  (let [history [(obs)                                        ; complete
                 (obs {:strip #{:tallies}})
                 (obs {:strip #{:admitted-count :flags}})]
        [_ audit] (mo/build-missingness-observation "audit-mv-1" history)
        f      (get-in audit [:missingness :fields])]
    ;; additive counts, never netted
    (is (= 2 (:present-in (get f :tallies))))
    (is (= 1 (:absent-in  (get f :tallies))))
    (is (= 2 (:present-in (get f :admitted-count))))
    (is (= 1 (:absent-in  (get f :admitted-count))))
    (is (= 2 (:present-in (get f :flags))))
    (is (= 1 (:absent-in  (get f :flags))))
    ;; records whose fields were never absent still count full
    (is (= 1 (get-in audit [:flags :records-with-full-fields])))
    (is (true? (get-in audit [:flags :field-absence-present])))
    ;; structural refusals audited too: a stripped record WOULD show absence,
    ;; but a conformant history with intact refusals shows none
    (is (= 3 (:present-in (get f :ranking-forbidden))))
    (is (= 0 (:absent-in  (get f :ranking-forbidden))))
    ;; coverage always partial
    (is (= :partial (get-in audit [:flags :coverage])))))

(deftest values-never-consumed
  ;; re-valuing a tally leaves the missingness observation byte-identical
  (let [a (mo/build-missingness-observation "audit-mv-1" [(obs {:tallies {:tally/scholarly-citation 3}})
                                                          (obs {:tallies {:tally/scholarly-citation 999 :tally/retraction 5}})
                                                          (obs {:strip #{:tallies}})])
        b (mo/build-missingness-observation "audit-mv-1" [(obs {:tallies {:tally/scholarly-citation -7}})
                                                          (obs {:tallies {}})
                                                          (obs {:strip #{:tallies}})])]
    (is (= (pr-str a) (pr-str b)))))

(deftest missing-is-not-zero
  ;; an absent field is absent, never folded into a zero count
  (let [[_ audit] (mo/build-missingness-observation "audit-mv-1" [(obs {:strip #{:flags}})])
        f (get-in audit [:missingness :fields :flags])]
    (is (= 0 (:present-in f)))
    (is (= 1 (:absent-in  f)))
    ;; flags surface it as absence
    (is (true? (get-in audit [:flags :field-absence-present])))
    (is (contains? (set (get-in audit [:flags :fields-never-present])) :flags))))

(deftest determinism
  (let [run (fn [] (pr-str (mo/build-missingness-observation "audit-mv-1" [(obs) (obs {:strip #{:tallies}})])))]
    (is (= (run) (run)))))

(deftest refresh-append-only
  (let [[_ a1] (mo/build-missingness-observation "audit-mv-1" [(obs)])
        [_ a2] (mo/build-missingness-observation "audit-mv-1" [(obs) (obs {:strip #{:tallies}})])
        h1     (mo/refresh [] a1)
        h2     (mo/refresh h1 a2)]
    (is (= [a1] h1))
    (is (= [a1 a2] h2))
    (is (= a1 (first h2)))                     ; prior record untouched
    (is (= [a1 a2] (mo/history-records h2)))))

(deftest hyakka-proposal-and-readback
  (let [[_ audit] (mo/build-missingness-observation "audit-mv-1" [(obs) (obs {:strip #{:flags}})])
        proposal  (mo/hyakka-proposal audit)]
    ;; empty audit proposes nothing
    (is (nil? (mo/hyakka-proposal {:history-size 0})))
    (is (some? (:proposal/dedupe-key proposal)))
    ;; dedupe-key is deterministic and order-insensitive over maps
    (is (= (:proposal/dedupe-key proposal) (mo/dedupe-key audit)))
    ;; honest readback accepted
    (is (true? (mo/hyakka-readback-accept? proposal proposal)))
    ;; tampering refused: stripped refusal flags
    (is (false? (mo/hyakka-readback-accept? proposal (dissoc proposal :proposal/ranking-forbidden))))
    (is (false? (mo/hyakka-readback-accept? proposal (dissoc proposal :proposal/causal-claims-forbidden))))
    (is (false? (mo/hyakka-readback-accept? proposal (dissoc proposal :proposal/imputation-forbidden))))
    ;; injected ranking or claims
    (is (false? (mo/hyakka-readback-accept? proposal (assoc proposal :proposal/ranking [{:r 1}]))))
    (is (false? (mo/hyakka-readback-accept? proposal (assoc proposal :proposal/claims [{:claim "x"}]))))
    ;; injected imputed fields — the exact thing this contract forbids
    (is (false? (mo/hyakka-readback-accept? proposal (assoc proposal :proposal/imputed-fields {:flags 0}))))
    ;; stripped missingness flag
    (is (false? (mo/hyakka-readback-accept? proposal
                                            (update proposal :proposal/flags dissoc :missing-is-unmeasured))))
    ;; coverage upgraded to a completeness claim → refuse
    (is (false? (mo/hyakka-readback-accept? proposal (assoc proposal :proposal/coverage :complete))))
    ;; altered missingness numbers → refuse
    (is (false? (mo/hyakka-readback-accept? proposal (update-in proposal [:proposal/missingness :fields] assoc :flags {:present-in 9 :absent-in 0}))))
    ;; dedupe-key mismatch → refuse
    (is (false? (mo/hyakka-readback-accept? proposal (assoc proposal :proposal/dedupe-key "other"))))))

(deftest complete-history-has-no-absence
  (let [[_ audit] (mo/build-missingness-observation "audit-mv-1" [(obs) (obs)])]
    (is (false? (get-in audit [:flags :field-absence-present])))
    (is (= 2 (get-in audit [:flags :records-with-full-fields])))
    (is (empty? (get-in audit [:flags :fields-never-present])))
    (is (= :partial (get-in audit [:flags :coverage])))))   ; still partial — history ≠ world

(defn ^:export run []
  (run-tests 'analytics.missingness-observation-test))
