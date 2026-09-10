(ns analytics.observation-conflict
  "observation-conflict/v1 — bounded conflict audit over a history of
  influence-observation/v1 records (see analytics.impact-observation).

  This contract answers one auditable question only: **within one logical
  identity (subject + window + method-version), do independently produced
  records agree?** It is a conflict report, not a reconciliation:

    * conflicting records are flagged and preserved VERBATIM — both values
      travel with the data; no averaging, no netting, no winner;
    * reconciliation is a structural refusal (:reconciliation-forbidden) —
      choosing between conflicting observations would silently author
      evidence, and this contract does not do that;
    * identical repeats are reported as `duplicate-records-present` and the
      counts stay ADDITIVE — duplicates are never silently deduplicated,
      because a repeat observation is itself a fact about the pipeline;
    * provenance is preserved verbatim: every distinct tallies value is
      carried into the derived record exactly as observed;
    * coverage is always :partial — agreement inside the supplied history
      says nothing about data outside it;
    * no ranking, no causal claim, no derived narrative.

  Pure functions only: no network, no clock, no file I/O. Determinism is a
  test fixture (byte-identical pr-str across runs).")

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
;; Logical identity — one subject inside one window under one method-version
;; ---------------------------------------------------------------------------

(defn identity-key
  "The identity a record's conflict status is judged within: subject verbatim,
  window bounds, method-version verbatim. Sorted into a deterministic string."
  [o]
  (pr-str {:subject (:subject o)
           :window {:from (:from (:window o)) :to (:to (:window o))}
           :method-version (:method-version o)}))

(defn- tallies-fingerprint
  "Deterministic, order-independent comparison value for one record's tallies."
  [o]
  (pr-str (into (sorted-map) (:tallies o))))

(defn- analyze-group
  "[records] for one identity -> group map. All records are preserved; distinct
  tallies variants are reported verbatim and additively (never merged)."
  [records]
  (let [variants (distinct (map tallies-fingerprint records))
        conflicting? (> (count variants) 1)]
    {:record-count (count records)
     :distinct-tallies-variants variants
     :conflicting conflicting?
     ;; Reconciliation is refused structurally — there is no resolved value.
     :reconciliation nil
     :reconciliation-forbidden true
     :records records}))

;; ---------------------------------------------------------------------------
;; Derived conflict audit — additive rollup only
;; ---------------------------------------------------------------------------

(defn derive-groups
  "Group the conformant history by logical identity and report each group.
  Groups are returned sorted by identity string so iteration order never
  leaks into the derived record."
  [history]
  (->> history
       (group-by identity-key)
       (into (sorted-map) (map (fn [[k rs]] [k (analyze-group rs)])))))

(defn derive-flags
  "Uncertainty/coverage flags over the group audit. Disagreement produces the
  flag — and produces it for the whole audit, never a silent pass-through."
  [history]
  (let [groups (derive-groups history)
        groups-vals (vals groups)
        conflicting-ids (sort (into [] (keep (fn [[k g]] (when (:conflicting g) k))
                                             groups)))]
    {:conflicting-groups (count conflicting-ids)
     :conflicting-identities conflicting-ids
     :duplicate-records-present
     (boolean (some #(and (> (long (:record-count %)) 1) (not (:conflicting %)))
                    groups-vals))
     :single-record-groups
     (count (filter #(= 1 (long (:record-count %))) groups-vals))
     :empty-history (empty? history)
     :method-versions (sort (into [] (distinct (map :method-version history))))
     :coverage :partial}))

(defn build-conflict-observation
  "Build one observation-conflict/v1 record over `history` (a vector of
  influence-observation/v1 maps). `method-version` is caller-supplied and
  stamped verbatim. A non-conformant record anywhere in the history refuses
  the whole audit — a poisoned input is not silently analyzed as if clean.
  Structural refusals are hardwired."
  [method-version history]
  (if (not (vector? history))
    [:rejected :history-not-vector]
    (if-let [bad (first (remove observation-conformant? history))]
      [:rejected :non-conformant-observation-in-history]
      (do (assert (seq method-version) "method-version is required")
          [:accepted
           {:contract "observation-conflict"
            :version "v1"
            :method-version method-version
            :history-size (count history)
            :coverage-window (when (seq history)
                               {:from (apply min (map #(get-in % [:window :from]) history))
                                :to (apply max (map #(get-in % [:window :to]) history))})
            :groups (derive-groups history)
            :flags (derive-flags history)
            :aggregate nil
            :aggregation-forbidden true
            :ranking nil
            :ranking-forbidden true
            :causal-claims-forbidden true
            :claims []}]))))

;; ---------------------------------------------------------------------------
;; Append-only refresh history
;; ---------------------------------------------------------------------------

(defn refresh
  "Append a new conflict observation onto the history of conflict records.
  Prior records are immutable; nothing is rewritten."
  [history conflict-obs]
  (conj (vec history) conflict-obs))

(defn history-records
  "The append-only list of prior conflict records."
  [history]
  (vec history))

;; ---------------------------------------------------------------------------
;; Hyakka proposal / readback
;; ---------------------------------------------------------------------------

(defn dedupe-key
  "Deterministic identity for one conflict audit. Sorted maps and vectors so
  iteration order never leaks into the key. Pure string — no crypto, no clock."
  [conflict-obs]
  (when (map? conflict-obs)
    (str "observation-conflict/v1:"
         (pr-str {:history-size (:history-size conflict-obs)
                  :coverage-window (:coverage-window conflict-obs)
                  :method-version (:method-version conflict-obs)
                  :groups (:groups conflict-obs)
                  :flags (select-keys (:flags conflict-obs)
                                      [:conflicting-groups
                                       :duplicate-records-present])}))))

(defn hyakka-proposal
  "Proposal payload for the Hyakka wiki, or nil when the history is empty —
  an empty history measures nothing and proposing a record for it would dress
  absence up as data."
  [conflict-obs]
  (when (and (map? conflict-obs) (pos? (long (or (:history-size conflict-obs) 0))))
    {:proposal/type :observation-conflict
     :proposal/dedupe-key (dedupe-key conflict-obs)
     :proposal/contract "observation-conflict/v1"
     :proposal/method-version (:method-version conflict-obs)
     :proposal/history-size (:history-size conflict-obs)
     :proposal/coverage-window (:coverage-window conflict-obs)
     :proposal/groups (:groups conflict-obs)
     :proposal/flags (:flags conflict-obs)
     :proposal/coverage :partial
     :proposal/aggregate nil
     :proposal/aggregation-forbidden true
     :proposal/ranking nil
     :proposal/ranking-forbidden true
     :proposal/causal-claims-forbidden true
     :proposal/claims []
     :proposal/note "conflict-report-not-reconciliation-conflicting-records-preserved-verbatim"}))

(defn hyakka-readback-accept?
  "Accept a readback only if it is still the record this actor proposed: same
  contract, same dedupe-key, same method-version, same groups and flags, same
  history size — and the structural refusals not stripped. Anything else is
  tampering; refuse."
  [proposal readback]
  (and (map? readback)
       (= "observation-conflict/v1" (:proposal/contract readback))
       (some? (:proposal/dedupe-key readback))
       (= (:proposal/dedupe-key proposal) (:proposal/dedupe-key readback))
       (= (:proposal/method-version proposal) (:proposal/method-version readback))
       (= (:proposal/history-size proposal) (:proposal/history-size readback))
       (= (:proposal/coverage-window proposal) (:proposal/coverage-window readback))
       (= (:proposal/groups proposal) (:proposal/groups readback))
       (= (:proposal/flags proposal) (:proposal/flags readback))
       (some? (:proposal/flags readback))
       (contains? (:proposal/flags readback) :conflicting-groups)
       (= :partial (:proposal/coverage readback))
       (nil? (:proposal/aggregate readback))
       (true? (:proposal/aggregation-forbidden readback))
       (true? (:proposal/ranking-forbidden readback))
       (true? (:proposal/causal-claims-forbidden readback))
       (nil? (:proposal/ranking readback))
       (empty? (:proposal/claims readback))))
