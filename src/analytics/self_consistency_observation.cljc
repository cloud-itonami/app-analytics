(ns analytics.self-consistency-observation
  "self-consistency-observation/v1 — bounded internal-consistency audit over a
  history of influence-observation/v1 records that share ONE identity
  (subject + window + method-version).

  This contract answers one auditable question only: **within one identity,
  does each stored record's own flag surface agree with its own tally/count
  surface?** Every prior contract audits a record against external inputs
  (notices, authority maps, source vocabularies, the window, each other);
  this one closes the remaining gap — the record against itself:

    * exactly THREE enumerated pairs are audited, presence-only:
      (1) :retraction-present (flag) vs :tally/retraction (tally key)
      (2) :correction-present (flag)  vs :tally/correction (tally key)
      (3) :admitted-count vs the additive sum of the record's tallies
      (each admitted signal contributes exactly 1 to one dimension, so the
      sum must equal :admitted-count — a disagreement is a finding);
    * a disagreement is FLAGGED and both sides travel VERBATIM — never
      repaired, reweighted, or corrected (`:repair-forbidden true`,
      structurally forbidden; the auditor has no authority to rewrite a
      stored record);
    * flags that cannot be verified from the record surface alone (e.g.
      :duplicate-content-hash, which depends on raw signals this audit
      never sees) are ENUMERATED under :unaudited-flag-keys, never guessed
      at — unverifiable is not identical-to-true and not identical-to-false;
    * counts are ADDITIVE and never netted; identity is fixed: a history
      that mixes subjects, windows or method-versions is refused as
      :identity-mismatch rather than merged;
    * a record missing one audited side reports :side-absent — absence is
      a finding, never folded into a shared value (`missing-is-unmeasured`);
    * coverage is always :partial — an audit over the supplied history says
      nothing about records outside it;
    * no ranking, no causal claim, no derived narrative.

  Self-contained by design: no dependency on any other analytics namespace.
  Pure functions only: no network, no clock, no file I/O. Determinism is a
  test fixture (byte-identical pr-str across runs).")

;; ---------------------------------------------------------------------------
;; Identity — subject + window + method-version (local, byte-identical
;; semantics to the other observation contracts; kept private so this module
;; stays self-contained and loads on its own branch)
;; ---------------------------------------------------------------------------

(defn- identity-key
  [o]
  (pr-str {:subject (:subject o)
           :window {:from (:from (:window o)) :to (:to (:window o))}
           :method-version (:method-version o)}))

;; ---------------------------------------------------------------------------
;; Audited surface — the three enumerated pairs. Anything outside this set is
;; enumerated as unaudited, never silently skipped and never guessed at.
;; ---------------------------------------------------------------------------

(def audited-flag-keys
  "Flag keys this contract can check against the record's own tally surface."
  #{:retraction-present :correction-present})

(def unaudited-flag-keys-known
  "Flag keys carried by influence-observation/v1 that this contract CANNOT
  verify from the record surface alone. Every observed flag key outside the
  audited set — these and any novel key — is enumerated under
  :unaudited-flag-keys, never evaluated."
  #{:missing-is-unmeasured :excluded-signals-present :single-source-dependency
    :duplicate-content-hash :coverage})

(def ^:private audited-tally-keys
  {:retraction-present :tally/retraction
   :correction-present :tally/correction})

;; ---------------------------------------------------------------------------
;; Validation — only influence-observation/v1-shaped records are consumed
;; ---------------------------------------------------------------------------

(defn- observation-conformant? [o]
  (and (map? o)
       (= "influence-observation" (:contract o))
       (= "v1" (:version o))
       (map? (:window o))
       (integer? (:from (:window o)))
       (integer? (:to (:window o)))
       (<= (:from (:window o)) (:to (:window o)))
       (seq (:method-version o))
       (map? (:tallies o))
       (map? (:flags o))
       (nil? (:ranking o))
       (true? (:ranking-forbidden o))
       (true? (:causal-claims-forbidden o))))

;; ---------------------------------------------------------------------------
;; Derived per-record findings — presence-only, additive, never repaired
;; ---------------------------------------------------------------------------

(defn- flag-vs-tally
  "One flag/tally pair verdict. Returns
  {:flag-key k :tally-key tk :flag-value v-or-nil
   :flag-side-absent b :tally-present b :agrees bool-or-:side-absent}.
  The tally side is ALWAYS decidable — a tally key that is absent is a
  present fact (zero retraction tallies recorded), never missing data, so
  the pair is judged against it. Only an absent FLAG side is undecidable
  and reports :side-absent — a finding, never a shared default."
  [o flag-key]
  (let [tk      (audited-tally-keys flag-key)
        fv      (get (:flags o) flag-key :side-absent)
        tp      (contains? (:tallies o) tk)
        agrees  (if (= fv :side-absent)
                  :side-absent
                  (= (boolean fv) tp))]
    {:flag-key flag-key
     :tally-key tk
     :flag-value (when (not= fv :side-absent) fv)
     :flag-side-absent (= fv :side-absent)
     :tally-present tp
     :agrees agrees}))

(defn- count-agreement
  "Admitted-count vs additive tally-sum. Both sides travel even when they
  disagree; nothing is reweighted. Missing :admitted-count is :side-absent."
  [o]
  (let [ac   (get o :admitted-count :side-absent)
        tsum (reduce + 0 (vals (:tallies o)))
        agrees (if (= ac :side-absent) :side-absent (= (long ac) (long tsum)))]
    {:admitted-count (when (not= ac :side-absent) ac)
     :admitted-count-side-absent (= ac :side-absent)
     :tally-sum tsum
     :agrees agrees}))

(defn derive-consistency
  "Per-record findings over a conformant, single-identity history. Records
  travel with their original index; findings are additive and never netted."
  [history]
  (mapv (fn [i o]
          (let [pairs  (mapv #(flag-vs-tally o %) (sort audited-flag-keys))
                counts (count-agreement o)
                unaudited (sort (into [] (distinct
                                          (remove #(contains? audited-flag-keys %)
                                                  (keys (:flags o))))))]
            {:record-index i
             :flag-verdicts pairs
             :count-verdict counts
             :unaudited-flag-keys unaudited
             :mismatch-present
             (boolean (or (some #(and (not= :side-absent (:agrees %))
                                      (not (:agrees %)))
                                pairs)
                          (and (not= :side-absent (:agrees counts))
                               (not (:agrees counts)))))}))
        (range)
        history))

(defn derive-flags
  "Uncertainty/coverage flags over the consistency audit. Disagreement
  produces the flag; agreement never cancels the boundary."
  [history findings]
  {:missing-is-unmeasured true
   :empty-history (empty? history)
   :mismatch-present (boolean (some :mismatch-present findings))
   :records-with-mismatch (count (filter :mismatch-present findings))
   :side-absent-present
   (boolean (some (fn [f]
                    (or (some :flag-side-absent (:flag-verdicts f))
                        (:admitted-count-side-absent (:count-verdict f))))
                  findings))
   :repair-forbidden true
   :coverage :partial})

(defn build-self-consistency-observation
  "Build one self-consistency-observation/v1 record over `history` (a vector
  of influence-observation/v1 maps). All records must share one identity —
  same subject, same window, same method-version — or the whole audit is
  refused as [:rejected :identity-mismatch]. A non-conformant record anywhere
  refuses the whole audit. `method-version` (the audit's own method version)
  is caller-supplied and stamped verbatim. Structural refusals are hardwired."
  [method-version history]
  (cond
    (not (vector? history))
    [:rejected :history-not-vector]

    (empty? history)
    [:rejected :empty-history]

    (not (seq method-version))
    [:rejected :missing-method-version]

    :else
    (if-let [bad (first (remove observation-conformant? history))]
      [:rejected :non-conformant-observation-in-history]
      (let [id     (identity-key (first history))
            mixed? (some #(not= id (identity-key %)) history)]
        (if mixed?
          [:rejected :identity-mismatch]
          (let [findings (derive-consistency history)]
            [:accepted
             {:contract "self-consistency-observation"
              :version "v1"
              :method-version method-version
              :subject (:subject (first history))
              :window (:window (first history))
              :history-size (count history)
              :findings findings
              :flags (derive-flags history findings)
              :repair-forbidden true
              :ranking nil
              :ranking-forbidden true
              :causal-claims-forbidden true
              :claims []}]))))))

;; ---------------------------------------------------------------------------
;; Append-only refresh history
;; ---------------------------------------------------------------------------

(defn refresh
  "Append a new consistency observation onto the history of audit records.
  Prior records are immutable; nothing is rewritten."
  [history audit]
  (conj (vec history) audit))

(defn history-records
  "The append-only list of prior audit records."
  [history]
  (vec history))

;; ---------------------------------------------------------------------------
;; Hyakka proposal / readback
;; ---------------------------------------------------------------------------

(defn dedupe-key
  "Deterministic identity for one consistency audit. Sorted maps so iteration
  order never leaks into the key. Pure string — no crypto, no clock."
  [audit]
  (when (map? audit)
    (str "self-consistency-observation/v1:"
         (pr-str {:subject (:subject audit)
                  :window (:window audit)
                  :history-size (:history-size audit)
                  :method-version (:method-version audit)
                  :findings (:findings audit)
                  :flags (select-keys (:flags audit)
                                      [:mismatch-present
                                       :records-with-mismatch
                                       :side-absent-present])}))))

(defn hyakka-proposal
  "Proposal payload for the Hyakka wiki, or nil for an empty audit — an audit
  over zero records measures nothing and proposing a record for it would
  dress absence up as data."
  [audit]
  (when (and (map? audit) (pos? (long (or (:history-size audit) 0))))
    {:proposal/type :self-consistency-observation
     :proposal/dedupe-key (dedupe-key audit)
     :proposal/contract "self-consistency-observation/v1"
     :proposal/method-version (:method-version audit)
     :proposal/subject (:subject audit)
     :proposal/window (:window audit)
     :proposal/history-size (:history-size audit)
     :proposal/findings (:findings audit)
     :proposal/flags (:flags audit)
     :proposal/coverage :partial
     :proposal/repair-forbidden true
     :proposal/ranking nil
     :proposal/ranking-forbidden true
     :proposal/causal-claims-forbidden true
     :proposal/claims []
     :proposal/note "internal-consistency-audit-not-impact-score-mismatch-flagged-never-repaired"}))

(defn hyakka-readback-accept?
  "Accept a readback only if it is still the record this actor proposed: same
  contract, same dedupe-key, same method-version, same findings and flags,
  same history size — and the structural refusals (no ranking, no causal
  claims, repair forbidden) not stripped. Anything else is tampering; refuse."
  [proposal readback]
  (and (map? readback)
       (= "self-consistency-observation/v1" (:proposal/contract readback))
       (some? (:proposal/dedupe-key readback))
       (= (:proposal/dedupe-key proposal) (:proposal/dedupe-key readback))
       (= (:proposal/method-version proposal) (:proposal/method-version readback))
       (= (:proposal/subject proposal) (:proposal/subject readback))
       (= (:proposal/window proposal) (:proposal/window readback))
       (= (:proposal/history-size proposal) (:proposal/history-size readback))
       (= (:proposal/findings proposal) (:proposal/findings readback))
       (= (:proposal/flags proposal) (:proposal/flags readback))
       (some? (:proposal/flags readback))
       (contains? (:proposal/flags readback) :missing-is-unmeasured)
       (= :partial (:proposal/coverage readback))
       (true? (:proposal/repair-forbidden readback))
       (true? (:proposal/ranking-forbidden readback))
       (true? (:proposal/causal-claims-forbidden readback))
       (nil? (:proposal/ranking readback))
       (nil? (:proposal/repaired-records readback))
       (empty? (:proposal/claims readback))))
