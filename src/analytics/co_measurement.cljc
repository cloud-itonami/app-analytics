(ns analytics.co-measurement
  "co-measurement-observation/v1 — bounded co-measurement audit over a
  history of influence-observation/v1 records (see
  analytics.impact-observation, analytics.observation-conflict,
  analytics.metrics-conflation).

  This contract answers one auditable question only: **within one identity
  (subject + window + method-version), which tally-key pairs were co-measured
  in the same record — presence only, never strength.** The audit is a
  negative-space contract, which is the point:

    * co-measurement is PRESENCE. Two keys appearing in the same record's
      tallies says nothing about their relationship. No coefficient, no
      strength, no direction, no lag, no weighting is ever computed —
      :correlation-computation-forbidden is hardwired and holds even when
      no pair exists, because the boundary is a property of this contract,
      not of the data;
    * a co-measured pair is never carried forward as causal, supporting,
      or explanatory evidence — :causal-inference-forbidden is hardwired;
    * pair membership is carried with the record that produced it
      (:pairs are attributed per identity group); nothing is netted,
      averaged, or reconciled across groups;
    * records are preserved verbatim inside their identity group;
    * coverage is always :partial — pair presence inside the supplied
      history says nothing about co-measurement outside it;
    * no ranking, no causal claim, no derived narrative.

  Pure functions only: no network, no clock, no file I/O. Determinism is a
  test fixture (byte-identical pr-str across runs).")

(require '[analytics.observation-conflict :as oc])

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
;; Per-identity co-measurement — presence-only pairs
;; ---------------------------------------------------------------------------

(defn- record-pairs
  "One record's tally keys -> the sorted vector of pairs co-measured in that
  single record. Presence only: a pair is a pair, not a relationship."
  [record]
  (let [ks (sort (keys (:tallies record)))]
    (vec (for [i (range (count ks))
               j (range (inc i) (count ks))]
           [(nth ks i) (nth ks j)]))))

(defn- analyze-group
  "[records] for one identity -> group map. Pairs are UNIONED across the
  group's records and attributed to the record count that carried each pair.
  The records themselves travel verbatim."
  [records]
  (let [pair->records (reduce
                       (fn [m [idx rec]]
                         (reduce (fn [m2 pair]
                                   (update m2 pair (fnil conj #{}) idx))
                                 m (record-pairs rec)))
                       {}
                       (map-indexed vector records))]
    {:pair-count (count pair->records)
     :pairs (vec (sort (keys pair->records)))
     :pairs-per-record-count (into (sorted-map)
                                   (map (fn [[p idxs]]
                                          [p (count idxs)]))
                                   pair->records)
     :record-count (count records)
     :records records}))

(defn derive-groups
  "Group the conformant history by logical identity (subject verbatim + window
  bounds + method-version, same key as observation-conflict) and compute each
  group's co-measured tally-key pairs. Groups are returned sorted so iteration
  order never leaks into the derived record."
  [history]
  (->> history
       (group-by oc/identity-key)
       (into (sorted-map)
             (map (fn [[k rs]] [k (analyze-group rs)])))))

(defn derive-flags
  "Uncertainty/coverage flags over the co-measurement audit. Both refusals are
  HARDWIRED — they hold even when no pair is present, because the boundary is
  a property of this contract, not of the data."
  [history]
  (let [groups (derive-groups history)]
    {:identities (count groups)
     :groups-with-pairs (count (filter pos? (map :pair-count (vals groups))))
     :pair-total (reduce + 0 (map :pair-count (vals groups)))
     :correlation-computation-forbidden true
     :causal-inference-forbidden true
     :empty-history (empty? history)
     :coverage :partial}))

(defn build-co-measurement-observation
  "Build one co-measurement-observation/v1 record over `history` (a vector of
  influence-observation/v1 maps). A non-vector history or a non-conformant
  record refuses the whole audit — a poisoned input is not silently analyzed
  as if clean. Structural refusals are hardwired."
  [history]
  (cond
    (not (vector? history))
    [:rejected :history-not-vector]
    :else
    (if-let [bad (first (remove observation-conformant? history))]
      [:rejected :non-conformant-observation-in-history]
      [:accepted
       {:contract "co-measurement-observation"
        :version "v1"
        :history-size (count history)
        :coverage-window (when (seq history)
                           {:from (apply min (map #(get-in % [:window :from]) history))
                            :to (apply max (map #(get-in % [:window :to]) history))})
        :groups (derive-groups history)
        :flags (derive-flags history)
        :pair-strengths nil
        :pair-strengths-forbidden true
        :correlation-computation-forbidden true
        :causal-inference-forbidden true
        :coverage :partial
        :aggregate nil
        :aggregation-forbidden true
        :ranking nil
        :ranking-forbidden true
        :causal-claims-forbidden true
        :claims []}])))

;; ---------------------------------------------------------------------------
;; Append-only refresh history
;; ---------------------------------------------------------------------------

(defn refresh
  "Append a new co-measurement observation onto the history of co-measurement
  records. Prior records are immutable; nothing is rewritten."
  [history co-measurement-obs]
  (conj (vec history) co-measurement-obs))

(defn history-records
  "The append-only list of prior co-measurement records."
  [history]
  (vec history))

;; ---------------------------------------------------------------------------
;; Hyakka proposal / readback
;; ---------------------------------------------------------------------------

(defn dedupe-key
  "Deterministic identity for one co-measurement audit. Sorted maps and
  vectors so iteration order never leaks into the key. Pure string — no
  crypto, no clock."
  [co-measurement-obs]
  (when (map? co-measurement-obs)
    (str "co-measurement-observation/v1:"
         (pr-str {:history-size (:history-size co-measurement-obs)
                  :coverage-window (:coverage-window co-measurement-obs)
                  :groups (:groups co-measurement-obs)
                  :flags (select-keys (:flags co-measurement-obs)
                                      [:identities
                                       :pair-total
                                       :correlation-computation-forbidden
                                       :causal-inference-forbidden])}))))

(defn hyakka-proposal
  "Proposal payload for the Hyakka wiki, or nil when the history is empty —
  an empty history measures nothing and proposing a record for it would dress
  absence up as data."
  [co-measurement-obs]
  (when (and (map? co-measurement-obs)
             (pos? (long (or (:history-size co-measurement-obs) 0))))
    {:proposal/type :co-measurement
     :proposal/dedupe-key (dedupe-key co-measurement-obs)
     :proposal/contract "co-measurement-observation/v1"
     :proposal/history-size (:history-size co-measurement-obs)
     :proposal/coverage-window (:coverage-window co-measurement-obs)
     :proposal/groups (:groups co-measurement-obs)
     :proposal/flags (:flags co-measurement-obs)
     :proposal/coverage :partial
     :proposal/pair-strengths nil
     :proposal/pair-strengths-forbidden true
     :proposal/aggregate nil
     :proposal/aggregation-forbidden true
     :proposal/ranking nil
     :proposal/ranking-forbidden true
     :proposal/causal-claims-forbidden true
     :proposal/claims []
     :proposal/note "presence-only-co-measurement-correlation-is-not-causation"}))

(defn hyakka-readback-accept?
  "Accept a readback only if it is still the record this actor proposed: same
  contract, same dedupe-key, same groups and flags, same history size — and
  the structural refusals not stripped. Anything else is tampering; refuse."
  [proposal readback]
  (and (map? readback)
       (= "co-measurement-observation/v1" (:proposal/contract readback))
       (some? (:proposal/dedupe-key readback))
       (= (:proposal/dedupe-key proposal) (:proposal/dedupe-key readback))
       (= (:proposal/history-size proposal) (:proposal/history-size readback))
       (= (:proposal/coverage-window proposal) (:proposal/coverage-window readback))
       (= (:proposal/groups proposal) (:proposal/groups readback))
       (= (:proposal/flags proposal) (:proposal/flags readback))
       (some? (:proposal/flags readback))
       (contains? (:proposal/flags readback) :pair-total)
       (true? (get-in readback [:proposal/flags :correlation-computation-forbidden]))
       (true? (get-in readback [:proposal/flags :causal-inference-forbidden]))
       (nil? (:proposal/pair-strengths readback))
       (true? (:proposal/pair-strengths-forbidden readback))
       (= :partial (:proposal/coverage readback))
       (nil? (:proposal/aggregate readback))
       (true? (:proposal/aggregation-forbidden readback))
       (true? (:proposal/ranking-forbidden readback))
       (true? (:proposal/causal-claims-forbidden readback))
       (nil? (:proposal/ranking readback))
       (empty? (:proposal/claims readback))))
