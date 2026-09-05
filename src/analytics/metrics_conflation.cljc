(ns analytics.metrics-conflation
  "metrics-conflation/v1 — bounded metric-vocabulary conflation audit over a
  history of influence-observation/v1 records (see
  analytics.impact-observation, analytics.observation-conflict).

  This contract answers one auditable question only: **do the tally keys that
  appear in a history stay inside the metric classes the caller declared, and
  is any conflation (attention treated as impact, funding treated as
  endorsement) structurally prevented?** The vocabulary is DECLARED by the
  caller and stamped verbatim — this contract never infers, guesses, or
  extends it:

    * a tally key the vocabulary classifies as :attention is reported as
      attention — it is never converted into, weighted into, or summed with
      any impact dimension (:attention-impact-conversion-forbidden is
      hardwired, whether or not attention keys are present);
    * a :funding-classified key is reported as funding and
      :funding-endorsement-inference-forbidden is hardwired — funding events
      travel with the data and imply nothing;
    * a tally key absent from the vocabulary is flagged
      `unknown-metric-keys-present` and carried verbatim under
      :unknown-metric-keys — it is never silently assigned a class, because
      assigning a class IS the judgment this contract must not make;
    * records are preserved verbatim inside their identity group; nothing is
      deduplicated, netted, or reconciled;
    * coverage is always :partial — vocabulary conformance inside the supplied
      history says nothing about data outside it;
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

(def metric-classes
  "The closed set of metric classes a caller may declare. Anything else is a
  malformed vocabulary, not a new insight."
  #{:impact-dimension :attention :funding :other})

(defn- vocabulary-conformant? [vocabulary]
  (and (map? vocabulary)
       (seq vocabulary)
       (every? keyword? (keys vocabulary))
       (every? #(contains? metric-classes %) (vals vocabulary))))

;; ---------------------------------------------------------------------------
;; Per-identity tally-key classification — declared classes only
;; ---------------------------------------------------------------------------

(defn- classify-keys
  "[tally-keys] under [vocabulary] -> {:class->keys ... :unknown-keys [...]}"
  [tally-keys vocabulary]
  (let [classified (group-by #(get vocabulary %) tally-keys)]
    {:impact-dimension-keys (sort (into [] (get classified :impact-dimension)))
     :attention-keys (sort (into [] (get classified :attention)))
     :funding-keys (sort (into [] (get classified :funding)))
     :other-keys (sort (into [] (get classified :other)))
     :unknown-keys (sort (into [] (get classified nil)))}))

(defn- analyze-group
  "[records] for one identity -> group map. Tally keys are UNIONED across the
  group's records and classified only against the declared vocabulary. The
  records themselves travel verbatim."
  [vocabulary records]
  (let [tally-keys (into (sorted-set)
                         (mapcat keys (map :tallies records)))]
    (assoc (classify-keys tally-keys vocabulary)
           :record-count (count records)
           :records records)))

(defn derive-groups
  "Group the conformant history by logical identity (subject verbatim + window
  bounds + method-version, same key as observation-conflict) and classify each
  group's tally keys against the declared vocabulary. Groups are returned
  sorted so iteration order never leaks into the derived record."
  [vocabulary history]
  (->> history
       (group-by oc/identity-key)
       (into (sorted-map)
             (map (fn [[k rs]] [k (analyze-group vocabulary rs)])))))

(defn derive-flags
  "Uncertainty/coverage flags over the conflation audit. The two conflation
  refusals are HARDWIRED — they hold even when no attention or funding key is
  present, because the boundary is a property of this contract, not of the
  data."
  [vocabulary history]
  (let [groups (derive-groups vocabulary history)
        union-of (fn [field] (sort (into [] (distinct (mapcat field (vals groups))))))]
    {:identities (count groups)
     :attention-keys-present (union-of :attention-keys)
     :funding-keys-present (union-of :funding-keys)
     :unknown-metric-keys (union-of :unknown-keys)
     :unknown-metric-keys-present (boolean (seq (union-of :unknown-keys)))
     :attention-impact-conversion-forbidden true
     :funding-endorsement-inference-forbidden true
     :empty-history (empty? history)
     :coverage :partial}))

(defn build-conflation-observation
  "Build one metrics-conflation/v1 record over `history` (a vector of
  influence-observation/v1 maps) against the caller-DECLARED `vocabulary`
  (tally-key -> metric class) stamped verbatim with `vocabulary-version`.
  A non-vector history, a non-conformant record, or a malformed vocabulary
  refuses the whole audit — a poisoned input is not silently analyzed as if
  clean. Structural refusals are hardwired."
  [vocabulary-version vocabulary history]
  (cond
    (not (vector? history))
    [:rejected :history-not-vector]
    (not (vocabulary-conformant? vocabulary))
    [:rejected :malformed-vocabulary]
    :else
    (if-let [bad (first (remove observation-conformant? history))]
      [:rejected :non-conformant-observation-in-history]
      (do (assert (seq vocabulary-version) "vocabulary-version is required")
          [:accepted
           {:contract "metrics-conflation"
            :version "v1"
            :vocabulary-version vocabulary-version
            :vocabulary (into (sorted-map) vocabulary)
            :history-size (count history)
            :coverage-window (when (seq history)
                               {:from (apply min (map #(get-in % [:window :from]) history))
                                :to (apply max (map #(get-in % [:window :to]) history))})
            :groups (derive-groups vocabulary history)
            :flags (derive-flags vocabulary history)
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
  "Append a new conflation observation onto the history of conflation records.
  Prior records are immutable; nothing is rewritten."
  [history conflation-obs]
  (conj (vec history) conflation-obs))

(defn history-records
  "The append-only list of prior conflation records."
  [history]
  (vec history))

;; ---------------------------------------------------------------------------
;; Hyakka proposal / readback
;; ---------------------------------------------------------------------------

(defn dedupe-key
  "Deterministic identity for one conflation audit. Sorted maps and vectors so
  iteration order never leaks into the key. Pure string — no crypto, no clock."
  [conflation-obs]
  (when (map? conflation-obs)
    (str "metrics-conflation/v1:"
         (pr-str {:vocabulary-version (:vocabulary-version conflation-obs)
                  :vocabulary (:vocabulary conflation-obs)
                  :history-size (:history-size conflation-obs)
                  :coverage-window (:coverage-window conflation-obs)
                  :groups (:groups conflation-obs)
                  :flags (select-keys (:flags conflation-obs)
                                      [:identities
                                       :unknown-metric-keys-present
                                       :attention-impact-conversion-forbidden
                                       :funding-endorsement-inference-forbidden])}))))

(defn hyakka-proposal
  "Proposal payload for the Hyakka wiki, or nil when the history is empty —
  an empty history measures nothing and proposing a record for it would dress
  absence up as data."
  [conflation-obs]
  (when (and (map? conflation-obs) (pos? (long (or (:history-size conflation-obs) 0))))
    {:proposal/type :metrics-conflation
     :proposal/dedupe-key (dedupe-key conflation-obs)
     :proposal/contract "metrics-conflation/v1"
     :proposal/vocabulary-version (:vocabulary-version conflation-obs)
     :proposal/vocabulary (:vocabulary conflation-obs)
     :proposal/history-size (:history-size conflation-obs)
     :proposal/coverage-window (:coverage-window conflation-obs)
     :proposal/groups (:groups conflation-obs)
     :proposal/flags (:flags conflation-obs)
     :proposal/coverage :partial
     :proposal/aggregate nil
     :proposal/aggregation-forbidden true
     :proposal/ranking nil
     :proposal/ranking-forbidden true
     :proposal/causal-claims-forbidden true
     :proposal/claims []
     :proposal/note "declared-vocabulary-only-attention-is-not-impact-funding-is-not-endorsement"}))

(defn hyakka-readback-accept?
  "Accept a readback only if it is still the record this actor proposed: same
  contract, same dedupe-key, same vocabulary and vocabulary-version, same
  groups and flags, same history size — and the structural refusals not
  stripped. Anything else is tampering; refuse."
  [proposal readback]
  (and (map? readback)
       (= "metrics-conflation/v1" (:proposal/contract readback))
       (some? (:proposal/dedupe-key readback))
       (= (:proposal/dedupe-key proposal) (:proposal/dedupe-key readback))
       (= (:proposal/vocabulary-version proposal) (:proposal/vocabulary-version readback))
       (= (:proposal/vocabulary proposal) (:proposal/vocabulary readback))
       (= (:proposal/history-size proposal) (:proposal/history-size readback))
       (= (:proposal/coverage-window proposal) (:proposal/coverage-window readback))
       (= (:proposal/groups proposal) (:proposal/groups readback))
       (= (:proposal/flags proposal) (:proposal/flags readback))
       (some? (:proposal/flags readback))
       (contains? (:proposal/flags readback) :unknown-metric-keys-present)
       (true? (get-in readback [:proposal/flags :attention-impact-conversion-forbidden]))
       (true? (get-in readback [:proposal/flags :funding-endorsement-inference-forbidden]))
       (= :partial (:proposal/coverage readback))
       (nil? (:proposal/aggregate readback))
       (true? (:proposal/aggregation-forbidden readback))
       (true? (:proposal/ranking-forbidden readback))
       (true? (:proposal/causal-claims-forbidden readback))
       (nil? (:proposal/ranking readback))
       (empty? (:proposal/claims readback))))
