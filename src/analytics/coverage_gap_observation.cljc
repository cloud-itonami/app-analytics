(ns analytics.coverage-gap-observation
  "coverage-gap-observation/v1 — bounded coverage/missingness rollup over a
  history of influence-observation/v1 records (see analytics.impact-observation).

  This contract answers one auditable question only: **for a fixed dimension
  set, where is the measurement absent?** It is a missingness rollup, not a
  score:

    * unmeasured dimensions are reported as `missing-is-unmeasured`, never
      as zero — absence of evidence is not evidence of absence;
    * counts are ADDITIVE and never netted: retraction/correction presence
      is preserved per observation, never cancelled against anything;
    * coverage is always :partial — a rollup over the supplied history says
      nothing about data outside it (completeness claims are structural
      refusals, same as in influence-observation/v1);
    * no ranking, no causal claim, no derived narrative.

  Pure functions only: no network, no clock, no file I/O. Determinism is a
  test fixture (byte-identical pr-str across runs).")

;; ---------------------------------------------------------------------------
;; Dimension set — mirrors analytics.impact-observation / research-scope.edn
;; ---------------------------------------------------------------------------

(def known-dimensions
  #{:scholarly-citation :replication :correction :retraction :policy-citation
    :patent-citation :standard-adoption :clinical-guideline-citation
    :dataset-or-software-reuse})

(def ^:private dimension-tally-field
  {:scholarly-citation          :tally/scholarly-citation
   :replication                 :tally/replication
   :correction                  :tally/correction
   :retraction                  :tally/retraction
   :policy-citation             :tally/policy-citation
   :patent-citation             :tally/patent-citation
   :standard-adoption           :tally/standard-adoption
   :clinical-guideline-citation :tally/clinical-guideline-citation
   :dataset-or-software-reuse   :tally/dataset-or-software-reuse})

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
;; Derived coverage record — additive rollup only
;; ---------------------------------------------------------------------------

(defn- dimension-measured-in? [o d]
  (let [field (dimension-tally-field d)]
    (and (contains? (:tallies o) field)
         (pos? (long (or (get (:tallies o) field) 0))))))

(defn derive-coverage
  "Per-dimension rollup over a conformant observation history.

  Returns {:dimensions {d {:measured-in n :unmeasured-in n}} ...}. `unmeasured-in`
  counts observations with no positive tally for d — missing, never zero-valued
  evidence. An observation where d was measured but only retracted/corrected
  still counts as measured-in for the ROLLUP because the tally field carries
  the raw observation count; polarity is never interpreted here."
  [history]
  (reduce (fn [acc d]
            (assoc-in acc [:dimensions d]
                      {:measured-in   (count (filter #(dimension-measured-in? % d)
                                                     history))
                       :unmeasured-in (count (remove #(dimension-measured-in? % d)
                                                     history))}))
          {:dimensions {}}
          (sort known-dimensions)))

(defn derive-flags
  "Uncertainty/coverage flags over the rollup. Absence produces the flag."
  [history]
  (let [coverage (derive-coverage history)
        dims     (:dimensions coverage)
        never-measured (sort (into [] (keep (fn [[d {:keys [measured-in]}]]
                                              (when (zero? (long measured-in)) d))
                                            dims)))]
    {:missing-is-unmeasured true
     :empty-history (empty? history)
     :unmeasured-observations-present
     (boolean (some (fn [[_ {:keys [unmeasured-in]}]] (pos? (long unmeasured-in)))
                    dims))
     :dimensions-never-measured never-measured
     :retraction-observations-count
     (count (filter #(contains? (:tallies %) :tally/retraction) history))
     :correction-observations-count
     (count (filter #(contains? (:tallies %) :tally/correction) history))
     :method-versions (sort (into [] (distinct (map :method-version history))))
     :coverage :partial}))

(defn build-coverage-observation
  "Build one coverage-gap-observation/v1 record over `history` (a vector of
  influence-observation/v1 maps, chronological). `method-version` is
  caller-supplied and stamped verbatim. A non-conformant record anywhere in
  the history refuses the whole rollup — partial input is not silently
  averaged in. Structural refusals are hardwired."
  [method-version history]
  (if (not (vector? history))
    [:rejected :history-not-vector]
    (if-let [bad (first (remove observation-conformant? history))]
      [:rejected :non-conformant-observation-in-history]
      (do (assert (seq method-version) "method-version is required")
          [:accepted
           {:contract "coverage-gap-observation"
            :version "v1"
            :method-version method-version
            :history-size (count history)
            :coverage-window (when (seq history)
                               {:from (apply min (map #(get-in % [:window :from]) history))
                                :to (apply max (map #(get-in % [:window :to]) history))})
            :coverage (derive-coverage history)
            :flags (derive-flags history)
            :ranking nil
            :ranking-forbidden true
            :causal-claims-forbidden true
            :claims []}]))))

;; ---------------------------------------------------------------------------
;; Append-only refresh history
;; ---------------------------------------------------------------------------

(defn refresh
  "Append a new coverage observation onto the history of coverage records.
  Prior records are immutable; nothing is rewritten."
  [history cov-obs]
  (conj (vec history) cov-obs))

(defn history-records
  "The append-only list of prior coverage records."
  [history]
  (vec history))

;; ---------------------------------------------------------------------------
;; Hyakka proposal / readback
;; ---------------------------------------------------------------------------

(defn dedupe-key
  "Deterministic identity for one coverage rollup. Sorted maps and vectors so
  iteration order never leaks into the key. Pure string — no crypto, no clock."
  [cov-obs]
  (when (map? cov-obs)
    (str "coverage-gap-observation/v1:"
         (pr-str {:history-size (:history-size cov-obs)
                  :coverage-window (:coverage-window cov-obs)
                  :method-version (:method-version cov-obs)
                  :dimensions (:coverage cov-obs)
                  :flags (select-keys (:flags cov-obs)
                                      [:dimensions-never-measured
                                       :retraction-observations-count
                                       :correction-observations-count])}))))

(defn hyakka-proposal
  "Proposal payload for the Hyakka wiki, or nil when the history is empty —
  an empty history measures nothing and proposing a record for it would dress
  absence up as data."
  [cov-obs]
  (when (and (map? cov-obs) (pos? (long (or (:history-size cov-obs) 0))))
    {:proposal/type :coverage-gap-observation
     :proposal/dedupe-key (dedupe-key cov-obs)
     :proposal/contract "coverage-gap-observation/v1"
     :proposal/method-version (:method-version cov-obs)
     :proposal/history-size (:history-size cov-obs)
     :proposal/coverage-window (:coverage-window cov-obs)
     :proposal/dimensions (:coverage cov-obs)
     :proposal/flags (:flags cov-obs)
     :proposal/coverage :partial
     :proposal/ranking nil
     :proposal/ranking-forbidden true
     :proposal/causal-claims-forbidden true
     :proposal/claims []
     :proposal/note "missingness-rollup-not-impact-score-unmeasured-is-not-zero"}))

(defn hyakka-readback-accept?
  "Accept a readback only if it is still the record this actor proposed: same
  contract, same dedupe-key, same method-version, same dimensions and flags,
  same history size — and the structural refusals not stripped. Anything else
  is tampering; refuse."
  [proposal readback]
  (and (map? readback)
       (= "coverage-gap-observation/v1" (:proposal/contract readback))
       (some? (:proposal/dedupe-key readback))
       (= (:proposal/dedupe-key proposal) (:proposal/dedupe-key readback))
       (= (:proposal/method-version proposal) (:proposal/method-version readback))
       (= (:proposal/history-size proposal) (:proposal/history-size readback))
       (= (:proposal/coverage-window proposal) (:proposal/coverage-window readback))
       (= (:proposal/dimensions proposal) (:proposal/dimensions readback))
       (= (:proposal/flags proposal) (:proposal/flags readback))
       (some? (:proposal/flags readback))
       (contains? (:proposal/flags readback) :missing-is-unmeasured)
       (= :partial (:proposal/coverage readback))
       (true? (:proposal/ranking-forbidden readback))
       (true? (:proposal/causal-claims-forbidden readback))
       (nil? (:proposal/ranking readback))
       (empty? (:proposal/claims readback))))
