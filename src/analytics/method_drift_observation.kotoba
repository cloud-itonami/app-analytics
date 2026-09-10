(ns analytics.method-drift-observation
  "method-drift-observation/v1 — bounded method-version comparability audit
  over a history of influence-observation/v1 records (see
  analytics.impact-observation, analytics.coverage-gap-observation).

  This contract answers one auditable question only: **does the observation
  history stay inside one method-version, and is it therefore even a candidate
  for aggregation?** It is a comparability audit, not a score:

    * when the history spans more than one :method-version, no cross-version
      aggregate is produced — the record reports per-version history sizes and
      flags the history :comparable false. Averaging across method versions
      would manufacture a trend that the methods never measured;
    * window overlap between records is reported as a flag (double-counting
      hazard), never corrected by netting or de-duplication — additive counts
      stay additive and the flag travels with the data;
    * multiple subjects in one history are flagged, never merged — subject
      identity is preserved verbatim;
    * every :method-version string is preserved verbatim in the derived
      record — provenance is not normalized;
    * coverage is always :partial; no ranking, no causal claim, no derived
      narrative.

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
;; Derived comparability facts — additive, never netted
;; ---------------------------------------------------------------------------

(defn- windows-overlap? [w1 w2]
  (and (<= (:from w1) (:to w2))
       (<= (:from w2) (:to w1))))

(defn derive-comparability
  "Per-fact comparability rollup over a conformant observation history.
  Returns {:method-versions ... :per-version-history-size ... :subjects ...}
  with windows/subjects preserved as verbatim provenance."
  [history]
  (let [versions    (sort (distinct (map :method-version history)))
        per-version (reduce (fn [acc v]
                              (assoc acc v (count (filter #(= v (:method-version %))
                                                          history))))
                            {}
                            versions)
        subjects    (vec (distinct (map (comp :subject/id :subject) history)))
        windows     (mapv :window history)
        overlapping (some (fn [[i w1]]
                            (some (fn [[j w2]]
                                    (when (< i j) (windows-overlap? w1 w2)))
                                  (map-indexed vector windows)))
                          (map-indexed vector windows))]
    {:method-versions versions
     :per-version-history-size per-version
     :subjects subjects
     :windows windows
     :overlapping-windows-present (boolean overlapping)}))

;; ---------------------------------------------------------------------------
;; Derived flags
;; ---------------------------------------------------------------------------

(defn derive-flags
  "Uncertainty/comparability flags over the rollup. Mixed method versions,
  overlapping windows, and mixed subjects each produce the flag; nothing is
  silently corrected."
  [history]
  (let [comp (derive-comparability history)]
    {:missing-is-unmeasured true
     :empty-history (empty? history)
     :mixed-method-versions (> (count (:method-versions comp)) 1)
     :comparable (boolean (and (seq history)
                               (= 1 (count (:method-versions comp)))
                               (not (:overlapping-windows-present comp))
                               (= 1 (count (:subjects comp)))))
     :overlapping-windows-present (:overlapping-windows-present comp)
     :mixed-subjects (> (count (:subjects comp)) 1)
     :method-versions (:method-versions comp)
     :coverage :partial}))

;; ---------------------------------------------------------------------------
;; Build the derived record — structural refusals are hardwired
;; ---------------------------------------------------------------------------

(defn build-method-drift-observation
  "Build one method-drift-observation/v1 record over `history` (a vector of
  influence-observation/v1 maps, chronological). `method-version` is the
  caller-supplied stamp for THIS audit and is recorded verbatim; it never
  overwrites the per-record versions it observed. A non-conformant record
  anywhere in the history refuses the whole audit — partial input is not
  silently averaged in. No cross-version aggregate count is ever emitted."
  [method-version history]
  (if (not (vector? history))
    [:rejected :history-not-vector]
    (if-let [bad (first (remove observation-conformant? history))]
      [:rejected :non-conformant-observation-in-history]
      (do (assert (seq method-version) "method-version is required")
          (let [comp (derive-comparability history)]
            [:accepted
             {:contract "method-drift-observation"
              :version "v1"
              :method-version method-version
              :history-size (count history)
              :coverage-window (when (seq history)
                                 {:from (apply min (map #(get-in % [:window :from]) history))
                                  :to (apply max (map #(get-in % [:window :to]) history))})
              :observed-method-versions (:method-versions comp)
              :per-version-history-size (:per-version-history-size comp)
              :subjects (:subjects comp)
              :windows (:windows comp)
              :flags (derive-flags history)
              :cross-version-aggregate nil
              :cross-version-aggregation-forbidden true
              :ranking nil
              :ranking-forbidden true
              :causal-claims-forbidden true
              :claims []}])))))

;; ---------------------------------------------------------------------------
;; Append-only refresh history
;; ---------------------------------------------------------------------------

(defn refresh
  "Append a new method-drift observation onto the history of audit records.
  Prior records are immutable; nothing is rewritten."
  [history drift-obs]
  (conj (vec history) drift-obs))

(defn history-records
  "The append-only list of prior audit records."
  [history]
  (vec history))

;; ---------------------------------------------------------------------------
;; Hyakka proposal / readback
;; ---------------------------------------------------------------------------

(defn dedupe-key
  "Deterministic identity for one comparability audit. Sorted maps and
  vectors so iteration order never leaks into the key. Pure string — no
  crypto, no clock."
  [drift-obs]
  (when (map? drift-obs)
    (str "method-drift-observation/v1:"
         (pr-str {:history-size (:history-size drift-obs)
                  :coverage-window (:coverage-window drift-obs)
                  :method-version (:method-version drift-obs)
                  :observed-method-versions (:observed-method-versions drift-obs)
                  :subjects (:subjects drift-obs)
                  :flags (select-keys (:flags drift-obs)
                                      [:mixed-method-versions
                                       :comparable
                                       :overlapping-windows-present
                                       :mixed-subjects])}))))

(defn hyakka-proposal
  "Proposal payload for the Hyakka wiki, or nil when the history is empty —
  an empty history measures nothing and proposing a record for it would dress
  absence up as data."
  [drift-obs]
  (when (and (map? drift-obs) (pos? (long (or (:history-size drift-obs) 0))))
    {:proposal/type :method-drift-observation
     :proposal/dedupe-key (dedupe-key drift-obs)
     :proposal/contract "method-drift-observation/v1"
     :proposal/method-version (:method-version drift-obs)
     :proposal/history-size (:history-size drift-obs)
     :proposal/coverage-window (:coverage-window drift-obs)
     :proposal/observed-method-versions (:observed-method-versions drift-obs)
     :proposal/per-version-history-size (:per-version-history-size drift-obs)
     :proposal/subjects (:subjects drift-obs)
     :proposal/flags (:flags drift-obs)
     :proposal/coverage :partial
     :proposal/cross-version-aggregate nil
     :proposal/cross-version-aggregation-forbidden true
     :proposal/ranking nil
     :proposal/ranking-forbidden true
     :proposal/causal-claims-forbidden true
     :proposal/claims []
     :proposal/note "comparability-audit-not-impact-score-mixed-versions-not-averaged"}))

(defn hyakka-readback-accept?
  "Accept a readback only if it is still the record this actor proposed: same
  contract, same dedupe-key, same method-version and observed versions, same
  subjects, flags and history size — and the structural refusals not stripped.
  Anything else is tampering; refuse."
  [proposal readback]
  (and (map? readback)
       (= "method-drift-observation/v1" (:proposal/contract readback))
       (some? (:proposal/dedupe-key readback))
       (= (:proposal/dedupe-key proposal) (:proposal/dedupe-key readback))
       (= (:proposal/method-version proposal) (:proposal/method-version readback))
       (= (:proposal/observed-method-versions proposal)
          (:proposal/observed-method-versions readback))
       (= (:proposal/per-version-history-size proposal)
          (:proposal/per-version-history-size readback))
       (= (:proposal/history-size proposal) (:proposal/history-size readback))
       (= (:proposal/coverage-window proposal) (:proposal/coverage-window readback))
       (= (:proposal/subjects proposal) (:proposal/subjects readback))
       (= (:proposal/flags proposal) (:proposal/flags readback))
       (some? (:proposal/flags readback))
       (contains? (:proposal/flags readback) :missing-is-unmeasured)
       (= :partial (:proposal/coverage readback))
       (nil? (:proposal/cross-version-aggregate readback))
       (true? (:proposal/cross-version-aggregation-forbidden readback))
       (true? (:proposal/ranking-forbidden readback))
       (true? (:proposal/causal-claims-forbidden readback))
       (nil? (:proposal/ranking readback))
       (empty? (:proposal/claims readback))))
