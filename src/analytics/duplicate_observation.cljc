(ns analytics.duplicate-observation
  "duplicate-observation/v1 — bounded near-duplicate audit over a history of
  influence-observation/v1 records that share ONE identity (subject + window +
  method-version).

  This contract answers one auditable question only: **within one identity,
  which stored records are value-identical on the audit's projection
  (:tallies + :admitted-count + :flags)?** Repeated refreshes can silently
  multiply the same observation; downstream consumers who sum over a history
  would count the same evidence twice. This audit makes duplication
  observable without removing anything:

    * duplicates are COUNTED, never removed — the audit is a finding surface,
      not a destructive dedupe (`:destructive-dedupe-forbidden true`);
    * counts are ADDITIVE and never netted: a record in a duplicate group
      never cancels another; singleton records stay in the denominator;
    * identity is fixed: a history that mixes subjects, windows or
      method-versions is refused as `:identity-mismatch` rather than merged;
    * the projection consumes only :tallies, :admitted-count and :flags —
      all already present on influence-observation/v1 records; no new data
      is invented and no field is imputed;
    * a record that is absent one of the projected fields projects that
      field as its own distinct marker (`:field-absent`), so absence is not
      folded into a shared zero — missing data stays missing;
    * empty histories measure nothing and are refused;
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
  [subject window method-version]
  (pr-str {:subject subject
           :window window
           :method-version method-version}))

;; ---------------------------------------------------------------------------
;; Validation — only influence-observation/v1-shaped records are consumed
;; ---------------------------------------------------------------------------

(defn- observation-conformant?
  "A record is conformant when every field that IS present carries a
  well-typed value. A present field with a malformed value poisons the
  audit and refuses it whole."
  [o]
  (and (map? o)
       (= "influence-observation" (:contract o))
       (= "v1" (:version o))
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
;; Projection fingerprint — the only surface the audit compares
;; ---------------------------------------------------------------------------

(defn- sorted-map-or-absent
  [o f]
  (if (contains? o f)
    (into (sorted-map) (get o f))
    :field-absent))

(defn- fingerprint
  "Deterministic projection of one record: :tallies, :admitted-count and
  :flags, each replaced by :field-absent when the field is missing (absence
  is not equal to a zero value — it gets its own distinct marker, so an
  absent :admitted-count never matches a present 0). Sorted maps so
  iteration order never leaks into the fingerprint."
  [o]
  (pr-str {:tallies (sorted-map-or-absent o :tallies)
           :admitted-count (get o :admitted-count :field-absent)
           :flags (sorted-map-or-absent o :flags)}))

;; ---------------------------------------------------------------------------
;; Derived duplication — additive, non-destructive
;; ---------------------------------------------------------------------------

(defn- group-summary
  [fingerprint groups]
  {:fingerprint fingerprint
   :record-count (count groups)
   :record-indices (vec (sort groups))})

(defn derive-duplication
  "Group conformant, single-identity records by projection fingerprint and
  report every group. Returns {:groups [..] :distinct-fingerprints n}. Groups
  are sorted by fingerprint so the output is deterministic. No record is
  removed or rewritten."
  [history]
  (let [indexed (map-indexed (fn [i o] [i (fingerprint o)]) history)
        by-fp   (reduce (fn [acc [i fp]]
                          (update acc fp conj i))
                        {} indexed)
        groups  (vec (sort-by (fn [[fp _]] fp) by-fp))]
    {:groups (mapv (fn [[fp idxs]] (group-summary fp idxs)) groups)
     :distinct-fingerprints (count groups)}))

(defn derive-flags
  "Uncertainty/coverage flags over the duplication audit. Duplicate groups
  are a finding; their absence never becomes a completeness claim."
  [duplication]
  (let [groups (:groups duplication)
        dup-groups (filter (fn [g] (> (long (:record-count g)) 1)) groups)]
    {:duplicate-groups-present (boolean (seq dup-groups))
     :duplicate-groups (count dup-groups)
     :duplicated-records (reduce (fn [acc g] (+ acc (long (:record-count g))))
                                 0 dup-groups)
     :singleton-records (count (filter (fn [g] (= 1 (long (:record-count g))))
                                       groups))
     :suggestion-only true
     :destructive-dedupe-forbidden true
     :coverage :partial}))

(defn build-duplicate-observation
  "Build one duplicate-observation/v1 record over `history` (a vector of
  influence-observation/v1 maps). All records must share one identity —
  same subject, same window, same method-version — or the whole audit is
  refused as [:rejected :identity-mismatch]. A non-conformant record
  anywhere refuses the whole audit. `method-version` (the audit's own
  method version) is caller-supplied and stamped verbatim. Structural
  refusals are hardwired."
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
          (let [duplication (derive-duplication history)]
            [:accepted
             {:contract "duplicate-observation"
              :version "v1"
              :method-version method-version
              :subject (:subject (first history))
              :window (:window (first history))
              :history-size (count history)
              :duplication duplication
              :flags (derive-flags duplication)
              :ranking nil
              :ranking-forbidden true
              :causal-claims-forbidden true
              :claims []}]))))))

;; ---------------------------------------------------------------------------
;; Append-only refresh history
;; ---------------------------------------------------------------------------

(defn refresh
  "Append a new duplicate observation onto the history of audit records.
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
  "Deterministic identity for one duplicate audit. Sorted maps so iteration
  order never leaks into the key. Pure string — no crypto, no clock."
  [audit]
  (when (map? audit)
    (str "duplicate-observation/v1:"
         (pr-str {:subject (:subject audit)
                  :window (:window audit)
                  :history-size (:history-size audit)
                  :method-version (:method-version audit)
                  :duplication (into (sorted-map)
                                     (map (fn [g]
                                            [(:fingerprint g)
                                             (:record-count g)]))
                                     (:groups (:duplication audit)))
                  :flags (select-keys (:flags audit)
                                      [:duplicate-groups-present
                                       :duplicate-groups
                                       :duplicated-records
                                       :singleton-records])}))))

(defn hyakka-proposal
  "Proposal payload for the Hyakka wiki, or nil for an empty audit — an audit
  over zero records measures nothing and proposing a record for it would
  dress absence up as data."
  [audit]
  (when (and (map? audit) (pos? (long (or (:history-size audit) 0))))
    {:proposal/type :duplicate-observation
     :proposal/dedupe-key (dedupe-key audit)
     :proposal/contract "duplicate-observation/v1"
     :proposal/method-version (:method-version audit)
     :proposal/subject (:subject audit)
     :proposal/window (:window audit)
     :proposal/history-size (:history-size audit)
     :proposal/duplication (:duplication audit)
     :proposal/flags (:flags audit)
     :proposal/coverage :partial
     :proposal/suggestion-only true
     :proposal/destructive-dedupe-forbidden true
     :proposal/ranking nil
     :proposal/ranking-forbidden true
     :proposal/causal-claims-forbidden true
     :proposal/claims []
     :proposal/note "duplicate-records-audit-not-dedupe-action-counted-not-removed"}))

(defn hyakka-readback-accept?
  "Accept a readback only if it is still the record this actor proposed: same
  contract, same dedupe-key, same method-version, same duplication and flags,
  same history size — and the structural refusals (no ranking, no causal
  claims, no destructive dedupe, suggestion-only) not stripped. Anything
  else is tampering; refuse."
  [proposal readback]
  (and (map? readback)
       (= "duplicate-observation/v1" (:proposal/contract readback))
       (some? (:proposal/dedupe-key readback))
       (= (:proposal/dedupe-key proposal) (:proposal/dedupe-key readback))
       (= (:proposal/method-version proposal) (:proposal/method-version readback))
       (= (:proposal/subject proposal) (:proposal/subject readback))
       (= (:proposal/window proposal) (:proposal/window readback))
       (= (:proposal/history-size proposal) (:proposal/history-size readback))
       (= (:proposal/duplication proposal) (:proposal/duplication readback))
       (= (:proposal/flags proposal) (:proposal/flags readback))
       (some? (:proposal/flags readback))
       (= :partial (:proposal/coverage readback))
       (true? (:proposal/suggestion-only readback))
       (true? (:proposal/destructive-dedupe-forbidden readback))
       (true? (:proposal/ranking-forbidden readback))
       (true? (:proposal/causal-claims-forbidden readback))
       (nil? (:proposal/ranking readback))
       (empty? (:proposal/claims readback))))
