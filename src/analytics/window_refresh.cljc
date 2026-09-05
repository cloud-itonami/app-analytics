(ns analytics.window-refresh
  "window-refresh-observation/v1 — bounded, provenance-preserving audit of the
  REFRESH HISTORY itself, for the cloud-itonami analytics actor.

  Prior contracts observe signals inside one window (influence-observation/v1)
  and audit where those signals came from (provenance-diversity-observation/v1).
  This contract audits the *measurement cadence*: given the append-only history
  of observation records, it derives — additively and without any clock of its
  own — which windows were measured, where the windows are discontinuous, and
  how stale the most recent observation is relative to a caller-supplied
  as-of instant.

  It answers exactly one auditable question: **what did the history actually
  measure, when, and where are the holes?**

    * windows are tallied additively — never combined into a trend. A trend,
      trajectory or momentum number would assert that consecutive window
      tallies are comparable causal evidence, which they are not
      (correlation is not causation; a time series is not impact);
    * staleness is ALWAYS relative to a caller-supplied `:as-of` instant —
      this module has no clock and never calls one. An observation is never
      \"outdated\" on its own, only measured-earlier-than-as-of;
    * a gap between windows is `:window-gap`, never zero and never
      interpolated. Missing measurements are missing-is-unmeasured;
    * history records that are not recognized observation shapes are flagged
      :unrecognized-record and enumerated — never silently averaged in;
    * coverage is always :partial — the audit says nothing about windows that
      were never run at all.

  Pure functions only: no network, no clock, no file I/O. Determinism is a
  test fixture (byte-identical pr-str across runs).")

;; ---------------------------------------------------------------------------
;; Record recognition — windows from a heterogeneous append-only history
;; ---------------------------------------------------------------------------

(def ^:private recognized-contracts
  #{"influence-observation/v1" "provenance-diversity-observation/v1"})

(defn- window-shape-ok? [{:keys [from to]}]
  (and (map? {:from from :to to})
       (integer? from) (integer? to) (< from to)))

(defn- recognized-record? [r]
  (and (map? r)
       (contains? recognized-contracts (:contract r))
       (map? (:window r))
       (window-shape-ok? (:window r))
       (integer? (:admitted-count r))))

(defn sort-records
  "History records sorted by window start (then end) so downstream derivation
  is order-independent. Unrecognized records are kept in a separate bucket and
  NEVER sorted into the window series."
  [history]
  (let [{recognized true, other false}
        (group-by #(boolean (recognized-record? %)) (vec history))]
    {:recognized (sort-by (juxt (comp :from :window) (comp :to :window))
                          (vec recognized))
     :unrecognized (vec other)}))

(defn- normalize-history [x]
  (if (sequential? x) (sort-records x) x))

(defn derive-window-series
  "Additive, per-record window facts over the recognized history: the sorted
  window list, per-window admitted-count, and the count of distinct windows.
  Nothing is aggregated across windows — a count of windows is not a trend.
  Accepts either the full sort-records result or a bare sequence of records."
  [x]
  (let [{:keys [recognized]} (normalize-history x)]
    {:windows (mapv (fn [r] {:from (-> r :window :from)
                             :to (-> r :window :to)
                             :admitted-count (:admitted-count r)
                             :contract (:contract r)})
                    recognized)
     :window-count (count recognized)
     :total-admitted-across-windows (reduce + 0 (map :admitted-count recognized))}))

(defn- disjoint-or-overlap? [w1 w2]
  (let [b (:to w1)
        c (:from w2)]
    (cond
      (< b c) :window-gap
      (< c b) :window-overlap
      :else :window-overlap)))

(defn derive-gaps
  "Between each consecutive pair of windows (sorted): :window-gap with the
  size of the hole, or :window-overlap with the size of the overlap. Gaps are
  named, never filled — an unmeasured stretch stays unmeasured."
  [series]
  (let [ws (:windows series)]
    (loop [i 0 acc {}]
      (if (< i (dec (count ws)))
        (let [a (nth ws i) b (nth ws (inc i))]
          (recur (inc i)
                 (update acc (disjoint-or-overlap? a b) (fnil inc 0))))
        acc))))

(defn derive-discontinuities
  "The explicit, ordered list of discontinuities between consecutive windows,
  each stating the two windows and the hole or overlap between them. This is
  the auditable evidence behind :derive-gaps — every count has a named place."
  [series]
  (let [ws (:windows series)]
    (mapv (fn [i]
            (let [a (nth ws i) b (nth ws (inc i))]
              (if (= :window-gap (disjoint-or-overlap? a b))
                {:kind :window-gap
                 :between [(:to a) (:from b)]
                 :size (- (:from b) (:to a))}
                {:kind :window-overlap
                 :between [(:from b) (:to a)]
                 :size (- (:to a) (:from b))})))
          (range (max 0 (dec (count ws)))))))

(defn derive-staleness
  "Staleness of the most recent window's end relative to a CALLER-SUPPLIED
  :as-of instant. This module never reads a clock: `as-of` is required to be
  an integer epoch-ms and the derivation is refused without it. Missing
  history is :no-observations, never a zero."
  [series as-of]
  (cond
    (not (integer? as-of))
    [:rejected :missing-or-invalid-as-of]
    (zero? (:window-count series))
    [:accepted {:kind :no-observations
                :measured-through nil
                :unmeasured-span nil}]
    :else
    (let [measured-through (transduce (map :to) max 0 (:windows series))]
      [:accepted
       {:kind :measured
        :measured-through measured-through
        :unmeasured-span (- as-of measured-through)}])))

;; ---------------------------------------------------------------------------
;; Derived observation — additive facts + flags only
;; ---------------------------------------------------------------------------

(defn- history-flags
  "Uncertainty / coverage flags over the history. Absence produces the flag,
  never a zero. Unrecognized records are enumerated whole — they are excluded
  from the window series but never discarded."
  [{:keys [recognized unrecognized]} series as-of]
  (let [stale (derive-staleness series as-of)]
    {:missing-is-unmeasured true
     :unrecognized-record-count (count unrecognized)
     :unrecognized-records
     (mapv (fn [r] {:contract (:contract r)}) unrecognized)
     :window-gaps-present (pos? (get (derive-gaps series) :window-gap 0))
     :window-overlaps-present (pos? (get (derive-gaps series) :window-overlap 0))
     :staleness (when (= :accepted (first stale)) (second stale))
     :as-of as-of
     :coverage :partial}))

(defn build-observation
  "Build one window-refresh-observation/v1 record from the append-only
  history.

  `method-version` is caller-supplied and stamped verbatim; `as-of` is the
  caller-supplied measurement instant (epoch-ms) staleness is computed
  against. The hard boundaries are structural: no trend, no trajectory, no
  growth rate, no clock of its own — only named windows, named gaps, and one
  relative staleness span."
  [method-version history as-of subject]
  (cond
    (not (or (nil? history) (sequential? history)))
    [:rejected :history-not-sequential]
    (not (integer? as-of))
    [:rejected :missing-or-invalid-as-of]
    :else
    (let [sorted (sort-records history)
          series (derive-window-series sorted)
          flags (history-flags sorted series as-of)]
      [:accepted
       {:contract "window-refresh-observation"
        :version "v1"
        :method-version method-version
        :subject subject
        :as-of as-of
        :window-series (:windows series)
        :window-count (:window-count series)
        :total-admitted-across-windows (:total-admitted-across-windows series)
        :discontinuities (derive-discontinuities series)
        :flags flags
        :trend nil
        :trend-forbidden true
        :growth-rate nil
        :growth-rate-forbidden true
        :causal-claims-forbidden true
        :ranking nil
        :ranking-forbidden true
        :claims []}])))

;; ---------------------------------------------------------------------------
;; Append-only refresh history of the refresher itself
;; ---------------------------------------------------------------------------

(defn refresh
  "Append one window-refresh observation onto the history of such records.
  Prior records are immutable; nothing is rewritten."
  [history obs]
  (conj (vec history) obs))

(defn history-records
  "The append-only list of prior window-refresh records."
  [history]
  (vec history))

;; ---------------------------------------------------------------------------
;; Hyakka proposal / readback
;; ---------------------------------------------------------------------------

(defn dedupe-key
  "Deterministic identity for one history audit. Sorted serialization so
  iteration order never leaks into the key. Pure string — no crypto, no
  clock."
  [obs]
  (when (map? obs)
    (str "window-refresh-observation/v1:"
         (pr-str {:subject (:subject obs)
                  :as-of (:as-of obs)
                  :method-version (:method-version obs)
                  :window-series (mapv (fn [w]
                                         (into (sorted-map) w))
                                       (:window-series obs))
                  :window-count (:window-count obs)}))))

(defn hyakka-proposal
  "Proposal payload for the Hyakka wiki, or nil when the history measured
  nothing — proposing a cadence record for an empty history would dress
  absence up as data."
  [obs]
  (when (and (map? obs) (pos? (long (or (:window-count obs) 0))))
    {:proposal/type :window-refresh-observation
     :proposal/dedupe-key (dedupe-key obs)
     :proposal/contract "window-refresh-observation/v1"
     :proposal/method-version (:method-version obs)
     :proposal/subject (:subject obs)
     :proposal/as-of (:as-of obs)
     :proposal/window-series (:window-series obs)
     :proposal/window-count (:window-count obs)
     :proposal/discontinuities (:discontinuities obs)
     :proposal/flags (:flags obs)
     :proposal/coverage :partial
     :proposal/trend nil
     :proposal/trend-forbidden true
     :proposal/growth-rate nil
     :proposal/growth-rate-forbidden true
     :proposal/causal-claims-forbidden true
     :proposal/ranking nil
     :proposal/ranking-forbidden true
     :proposal/claims []
     :proposal/note "cadence-audit-not-trend-gaps-are-not-zero-staleness-is-relative-to-as-of"}))

(defn hyakka-readback-accept?
  "Accept a readback only if it is still the record this actor proposed: same
  contract, same dedupe-key, same method-version, same window series and
  discontinuities, same flags — and the structural refusals (no trend, no
  growth rate, no ranking, no causal claims) not stripped. Anything else is
  tampering; refuse."
  [proposal readback]
  (and (map? readback)
       (= "window-refresh-observation/v1" (:proposal/contract readback))
       (some? (:proposal/dedupe-key readback))
       (= (:proposal/dedupe-key proposal) (:proposal/dedupe-key readback))
       (= (:proposal/method-version proposal) (:proposal/method-version readback))
       (= (:proposal/subject proposal) (:proposal/subject readback))
       (= (:proposal/as-of proposal) (:proposal/as-of readback))
       (= (:proposal/window-series proposal) (:proposal/window-series readback))
       (= (:proposal/window-count proposal) (:proposal/window-count readback))
       (= (:proposal/discontinuities proposal) (:proposal/discontinuities readback))
       (= (:proposal/flags proposal) (:proposal/flags readback))
       (some? (:proposal/flags readback))
       (contains? (:proposal/flags readback) :missing-is-unmeasured)
       (= :partial (:proposal/coverage readback))
       (true? (:proposal/trend-forbidden readback))
       (nil? (:proposal/trend readback))
       (true? (:proposal/growth-rate-forbidden readback))
       (nil? (:proposal/growth-rate readback))
       (true? (:proposal/ranking-forbidden readback))
       (true? (:proposal/causal-claims-forbidden readback))
       (nil? (:proposal/ranking readback))
       (empty? (:proposal/claims readback))))
