(ns analytics.missingness-observation
  "missingness-observation/v1 — bounded schema-field missingness audit over a
  history of influence-observation/v1 records that share ONE identity
  (subject + window + method-version).

  This contract answers one auditable question only: **within one identity,
  which mandatory fields of the observation schema are present vs absent in
  the stored records?** It is a missingness audit of the records themselves —
  a complement to analytics.coverage-gap-observation (which audits dimension
  absence, not field absence):

    * an absent field is reported as `absent-in`, never imputed and never
      zero — missing data stays missing (`missing-is-unmeasured`);
    * counts are ADDITIVE and never netted: presence in one record never
      cancels absence in another;
    * field VALUES are never consumed — the audit is presence-only, so
      re-valuing a record leaves the observation unchanged;
    * identity is fixed: a history that mixes subjects, windows or
      method-versions is refused as `:identity-mismatch` rather than merged;
    * imputation is structurally forbidden (`:imputation-forbidden true`) and
      there is no field in the output shape that could carry an imputed value;
    * coverage is always :partial — an audit over the supplied history says
      nothing about records outside it;
    * no ranking, no causal claim, no derived narrative.

  Self-contained by design: no dependency on any other analytics namespace
  beyond the influence-observation/v1 record shape it audits. Pure functions
  only: no network, no clock, no file I/O. Determinism is a test fixture
  (byte-identical pr-str across runs).")

;; ---------------------------------------------------------------------------
;; Identity — subject + window + method-version (local, byte-identical
;; semantics to the other observation contracts; kept private so this module
;; stays self-contained and loads on its own branch)
;; ---------------------------------------------------------------------------

(defn- identity-key
  [subject window method-version]
  (pr-str {:subject subject
           :window window
           :method-version method-version}))

;; ---------------------------------------------------------------------------
;; Field set — the influence-observation/v1 schema surface this contract
;; audits. Structural refusals (:ranking nil etc.) are audited too: a record
;; whose safety refusals were stripped IS a missingness finding.
;; ---------------------------------------------------------------------------

(def known-record-fields
  #{:contract :version :method-version :subject :window :tallies
    :admitted-count :flags :ranking :ranking-forbidden
    :causal-claims-forbidden :claims})

;; ---------------------------------------------------------------------------
;; Validation — only influence-observation/v1-shaped records are consumed
;; ---------------------------------------------------------------------------

(defn- observation-conformant?
  "A record is conformant when every field that IS present carries a
  well-typed value. Absence of an audited field is a FINDING (this is a
  missingness audit), not a refusal; a present field with a malformed value
  poisons the audit and refuses it whole."
  [o]
  (and (map? o)
       (= "influence-observation" (:contract o))
       (= "v1" (:version o))
       ;; window: absent → finding; present → well-formed
       (or (not (contains? o :window))
           (and (map? (:window o))
                (integer? (:from (:window o)))
                (integer? (:to (:window o)))
                (<= (:from (:window o)) (:to (:window o)))))
       (or (not (contains? o :method-version)) (seq (:method-version o)))
       (or (not (contains? o :subject)) (map? (:subject o)))
       (or (not (contains? o :tallies)) (map? (:tallies o)))
       (or (not (contains? o :admitted-count)) (integer? (:admitted-count o)))
       (or (not (contains? o :flags)) (map? (:flags o)))
       (or (not (contains? o :ranking)) (nil? (:ranking o)))
       (or (not (contains? o :ranking-forbidden))
           (true? (:ranking-forbidden o)))
       (or (not (contains? o :causal-claims-forbidden))
           (true? (:causal-claims-forbidden o)))
       (or (not (contains? o :claims)) (sequential? (:claims o)))))

;; ---------------------------------------------------------------------------
;; Derived missingness — additive, presence-only
;; ---------------------------------------------------------------------------

(defn- field-present? [o f]
  (contains? o f))

(defn derive-missingness
  "Per-field presence/absence rollup over a conformant, single-identity
  history. Returns {:fields {f {:present-in n :absent-in n}}}. `absent-in`
  counts records where the field is absent — missing, never zero-valued and
  never imputed. Field values are never read."
  [history]
  (reduce (fn [acc f]
            (assoc-in acc [:fields f]
                      {:present-in (count (filter #(field-present? % f) history))
                       :absent-in  (count (remove #(field-present? % f) history))}))
          {:fields {}}
          (sort known-record-fields)))

(defn derive-flags
  "Uncertainty/coverage flags over the missingness audit. Absence produces
  the flag; presence never cancels it."
  [history]
  (let [m      (derive-missingness history)
        fields (:fields m)
        never-present (sort (into [] (keep (fn [[f {:keys [present-in]}]]
                                             (when (zero? (long present-in)) f))
                                           fields)))]
    {:missing-is-unmeasured true
     :empty-history (empty? history)
     :field-absence-present
     (boolean (some (fn [[_ {:keys [absent-in]}]] (pos? (long absent-in)))
                    fields))
     :fields-never-present never-present
     :records-with-full-fields
     (count (filter (fn [o] (every? (fn [f] (field-present? o f))
                                    known-record-fields))
                    history))
     :coverage :partial}))

(defn build-missingness-observation
  "Build one missingness-observation/v1 record over `history` (a vector of
  influence-observation/v1 maps). All records must share one identity —
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
      (let [id (identity-key (:subject (first history))
                             (:window (first history))
                             (:method-version (first history)))
            mixed? (some #(not= id (identity-key (:subject %)
                                                 (:window %)
                                                 (:method-version %)))
                         history)]
        (if mixed?
          [:rejected :identity-mismatch]
          [:accepted
           {:contract "missingness-observation"
            :version "v1"
            :method-version method-version
            :subject (:subject (first history))
            :window (:window (first history))
            :history-size (count history)
            :missingness (derive-missingness history)
            :flags (derive-flags history)
            :imputation-forbidden true
            :ranking nil
            :ranking-forbidden true
            :causal-claims-forbidden true
            :claims []}])))))

;; ---------------------------------------------------------------------------
;; Append-only refresh history
;; ---------------------------------------------------------------------------

(defn refresh
  "Append a new missingness observation onto the history of audit records.
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
  "Deterministic identity for one missingness audit. Sorted maps so iteration
  order never leaks into the key. Pure string — no crypto, no clock."
  [audit]
  (when (map? audit)
    (str "missingness-observation/v1:"
         (pr-str {:subject (:subject audit)
                  :window (:window audit)
                  :history-size (:history-size audit)
                  :method-version (:method-version audit)
                  :fields (into (sorted-map) (:missingness audit))
                  :flags (select-keys (:flags audit)
                                      [:field-absence-present
                                       :fields-never-present
                                       :records-with-full-fields])}))))

(defn hyakka-proposal
  "Proposal payload for the Hyakka wiki, or nil for an empty audit — an audit
  over zero records measures nothing and proposing a record for it would
  dress absence up as data."
  [audit]
  (when (and (map? audit) (pos? (long (or (:history-size audit) 0))))
    {:proposal/type :missingness-observation
     :proposal/dedupe-key (dedupe-key audit)
     :proposal/contract "missingness-observation/v1"
     :proposal/method-version (:method-version audit)
     :proposal/subject (:subject audit)
     :proposal/window (:window audit)
     :proposal/history-size (:history-size audit)
     :proposal/missingness (:missingness audit)
     :proposal/flags (:flags audit)
     :proposal/coverage :partial
     :proposal/imputation-forbidden true
     :proposal/ranking nil
     :proposal/ranking-forbidden true
     :proposal/causal-claims-forbidden true
     :proposal/claims []
     :proposal/note "field-missingness-audit-not-impact-score-absent-is-not-zero"}))

(defn hyakka-readback-accept?
  "Accept a readback only if it is still the record this actor proposed: same
  contract, same dedupe-key, same method-version, same missingness and flags,
  same history size — and the structural refusals (no ranking, no causal
  claims, imputation forbidden) not stripped. Anything else is tampering;
  refuse."
  [proposal readback]
  (and (map? readback)
       (= "missingness-observation/v1" (:proposal/contract readback))
       (some? (:proposal/dedupe-key readback))
       (= (:proposal/dedupe-key proposal) (:proposal/dedupe-key readback))
       (= (:proposal/method-version proposal) (:proposal/method-version readback))
       (= (:proposal/subject proposal) (:proposal/subject readback))
       (= (:proposal/window proposal) (:proposal/window readback))
       (= (:proposal/history-size proposal) (:proposal/history-size readback))
       (= (:proposal/missingness proposal) (:proposal/missingness readback))
       (= (:proposal/flags proposal) (:proposal/flags readback))
       (some? (:proposal/flags readback))
       (contains? (:proposal/flags readback) :missing-is-unmeasured)
       (= :partial (:proposal/coverage readback))
       (true? (:proposal/imputation-forbidden readback))
       (true? (:proposal/ranking-forbidden readback))
       (true? (:proposal/causal-claims-forbidden readback))
       (nil? (:proposal/ranking readback))
       (nil? (:proposal/imputed-fields readback))
       (empty? (:proposal/claims readback))))
