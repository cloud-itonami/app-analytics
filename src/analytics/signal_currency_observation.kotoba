(ns analytics.signal-currency-observation
  "signal-currency-observation/v1 — bounded, provenance-preserving audit of
  SIGNAL CURRENCY: how old is the evidence that one measurement rests on,
  for the cloud-itonami analytics actor.

  influence-observation/v1 admits signals inside a measurement window and
  derives additive tallies. retraction-observation/v1 preserves negative
  evidence. duplicate-observation/v1 audits the stored history. None of them
  make the AGE of the admitted evidence observable. A window [from, to] says
  when a signal was OBSERVED, not how far the observation sits from the
  moment a consumer reads the tallies — and a tally that silently rests on
  years-old signals reads as current. This contract closes that hole.

  It answers exactly one auditable question: **relative to a caller-supplied
  as-of instant, which admitted signals are stale (older than a caller-
  supplied horizon) and which are current — per dimension, with the age
  extremes enumerated?**

    * staleness is RELATIVE to a caller-supplied `:as-of` and a caller-
      supplied `:stale-after` — this module has no clock and never calls one,
      and it invents no default horizon. Both missing or malformed refuse
      the audit whole rather than silently assuming a horizon;
    * a stale signal is FLAGGED and kept verbatim, never removed, decayed,
      reweighted, or discounted. A decay function would assert that an old
      observation counts less — which is a causal claim about evidence
      persistence this contract structurally forbids (`:decay-forbidden`);
    * stale is a measurement, not a verdict: `:stale-signals-present` says
      the evidence has age extremes, nothing more. No freshness score, no
      recency ranking of subjects, no trend across windows;
    * per-dimension boundaries are additive min/max over the supplied
      signals only — `:oldest-observed-at` / `:newest-observed-at`. They
      describe the batch, never extrapolate to a stream;
    * signals that do not carry the minimum auditable shape (:dimension,
      :observed-at, provenance) are enumerated as :unrecognized-signal —
      never silently folded into a count and never imputed a value;
    * an empty signal batch measures nothing and is refused;
    * coverage is always :partial — the audit says nothing about signals
      outside the supplied batch.

  Self-contained by design: no dependency on any other analytics namespace.
  Pure functions only: no network, no clock, no file I/O. Determinism is a
  test fixture (byte-identical pr-str across runs).")

;; ---------------------------------------------------------------------------
;; Validation — the minimum shape a signal must carry to be audited
;; ---------------------------------------------------------------------------

(def known-dimensions
  #{:scholarly-citation :replication :correction :retraction :policy-citation
    :patent-citation :standard-adoption :clinical-guideline-citation
    :dataset-or-software-reuse})

(defn- conformant-signal? [s]
  (and (map? s)
       (contains? known-dimensions (:dimension s))
       (integer? (:observed-at s))
       (seq (:source-url s))
       (integer? (:observed-at s))
       (:content-hash s)))

;; ---------------------------------------------------------------------------
;; Per-dimension currency — additive extremes over the supplied batch
;; ---------------------------------------------------------------------------

(defn- dimension-summary
  [dimension signals as-of stale-after]
  (let [ages (map (fn [s] (- as-of (:observed-at s))) signals)
        stale-count (count (filter #(> (long %) (long stale-after)) ages))]
    {:dimension dimension
     :signal-count (count signals)
     :stale-count stale-count
     :current-count (- (count signals) stale-count)
     :oldest-observed-at (apply min (map :observed-at signals))
     :newest-observed-at (apply max (map :observed-at signals))
     :oldest-age (- as-of (apply min (map :observed-at signals)))}))

(defn derive-currency
  "Group signals by dimension and derive per-dimension age extremes and
  stale/current counts. Returns {:dimensions [..] :unrecognized [..]}.
  Dimension entries are sorted by dimension name so batch order never leaks
  into the record. Unrecognized signals are enumerated whole — kept, never
  counted, never imputed."
  [signals as-of stale-after]
  (let [{conformant true, unrecognized false}
        (group-by #(boolean (conformant-signal? %)) (vec signals))
        by-dimension (group-by :dimension conformant)
        summaries (mapv (fn [[d ss]]
                          (dimension-summary d ss as-of stale-after))
                        (sort-by first by-dimension))]
    {:dimensions summaries
     :unrecognized (vec unrecognized)}))

(defn derive-flags
  "Uncertainty/coverage flags over the currency audit. Staleness is a
  finding; its absence is never a freshness claim."
  [currency]
  (let [dims (:dimensions currency)
        stale-total (reduce (fn [acc d] (+ acc (long (:stale-count d)))) 0 dims)]
    {:stale-signals-present (pos? stale-total)
     :stale-signals stale-total
     :current-signals (reduce (fn [acc d] (+ acc (long (:current-count d)))) 0 dims)
     :dimensions-measured (count dims)
     :unrecognized-signals (count (:unrecognized currency))
     :decay-forbidden true
     :suggestion-only true
     :coverage :partial}))

;; ---------------------------------------------------------------------------
;; Observation builder
;; ---------------------------------------------------------------------------

(defn build-signal-currency-observation
  "Build one signal-currency-observation/v1 record over `signals` (a vector
  of admitted-signal maps carrying :dimension, :observed-at and provenance),
  relative to caller-supplied `:as-of` and `:stale-after` (in the same time
  unit as :observed-at — the module converts nothing). Empty or non-vector
  batches, missing/malformed as-of or horizon refuse the audit whole.
  `method-version` is caller-supplied and stamped verbatim. Structural
  refusals are hardwired."
  [method-version signals {:keys [as-of stale-after]}]
  (cond
    (not (vector? signals))
    [:rejected :signals-not-vector]

    (empty? signals)
    [:rejected :empty-signal-batch]

    (not (seq method-version))
    [:rejected :missing-method-version]

    (or (not (integer? as-of)) (not (integer? stale-after)) (neg? stale-after))
    [:rejected :malformed-currency-scope]

    :else
    (let [currency (derive-currency signals as-of stale-after)]
      [:accepted
       {:contract "signal-currency-observation"
        :version "v1"
        :method-version method-version
        :as-of as-of
        :stale-after stale-after
        :signal-count (count signals)
        :currency currency
        :flags (derive-flags currency)
        :ranking nil
        :ranking-forbidden true
        :causal-claims-forbidden true
        :claims []}])))

;; ---------------------------------------------------------------------------
;; Append-only refresh history
;; ---------------------------------------------------------------------------

(defn refresh
  "Append a new currency audit onto the history of audit records. Prior
  records are immutable; nothing is rewritten."
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
  "Deterministic identity for one currency audit. Maps are sorted before
  serialization so iteration order never leaks into the key. Pure string —
  no crypto, no clock."
  [audit]
  (when (map? audit)
    (str "signal-currency-observation/v1:"
         (pr-str {:method-version (:method-version audit)
                  :as-of (:as-of audit)
                  :stale-after (:stale-after audit)
                  :signal-count (:signal-count audit)
                  :dimensions (into (sorted-map)
                                    (map (fn [d]
                                           [(:dimension d)
                                            (select-keys d [:signal-count
                                                            :stale-count
                                                            :current-count
                                                            :oldest-observed-at
                                                            :newest-observed-at])])
                                         (:dimensions (:currency audit))))
                  :flags (select-keys (:flags audit)
                                      [:stale-signals-present
                                       :stale-signals
                                       :current-signals
                                       :dimensions-measured
                                       :unrecognized-signals])}))))

(defn hyakka-proposal
  "Proposal payload for the Hyakka wiki, or nil for an empty audit — an
  audit over zero signals measures nothing and proposing a record for it
  would dress absence up as data."
  [audit]
  (when (and (map? audit) (pos? (long (or (:signal-count audit) 0))))
    {:proposal/type :signal-currency-observation
     :proposal/dedupe-key (dedupe-key audit)
     :proposal/contract "signal-currency-observation/v1"
     :proposal/method-version (:method-version audit)
     :proposal/as-of (:as-of audit)
     :proposal/stale-after (:stale-after audit)
     :proposal/signal-count (:signal-count audit)
     :proposal/currency (:currency audit)
     :proposal/flags (:flags audit)
     :proposal/coverage :partial
     :proposal/suggestion-only true
     :proposal/decay-forbidden true
     :proposal/ranking nil
     :proposal/ranking-forbidden true
     :proposal/causal-claims-forbidden true
     :proposal/claims []
     :proposal/note "signal-age-audit-flagged-not-decayed-stale-is-not-false"}))

(defn hyakka-readback-accept?
  "Accept a readback only if it is still the record this actor proposed: same
  contract, same dedupe-key, same method-version, same currency scope,
  per-dimension summaries and flags — and the structural refusals (no
  ranking, no causal claims, no decay, suggestion-only) not stripped.
  Anything else is tampering; refuse."
  [proposal readback]
  (and (map? readback)
       (= "signal-currency-observation/v1" (:proposal/contract readback))
       (some? (:proposal/dedupe-key readback))
       (= (:proposal/dedupe-key proposal) (:proposal/dedupe-key readback))
       (= (:proposal/method-version proposal) (:proposal/method-version readback))
       (= (:proposal/as-of proposal) (:proposal/as-of readback))
       (= (:proposal/stale-after proposal) (:proposal/stale-after readback))
       (= (:proposal/signal-count proposal) (:proposal/signal-count readback))
       (= (:proposal/currency proposal) (:proposal/currency readback))
       (= (:proposal/flags proposal) (:proposal/flags readback))
       (some? (:proposal/flags readback))
       (= :partial (:proposal/coverage readback))
       (true? (:proposal/suggestion-only readback))
       (true? (:proposal/decay-forbidden readback))
       (true? (:proposal/ranking-forbidden readback))
       (true? (:proposal/causal-claims-forbidden readback))
       (nil? (:proposal/ranking readback))
       (empty? (:proposal/claims readback))))
