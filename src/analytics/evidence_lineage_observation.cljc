(ns analytics.evidence-lineage-observation
  "evidence-lineage-observation/v1 — bounded replay audit over one stored
  influence-observation/v1 record and the raw signals that claim to have
  produced it (see analytics.impact-observation and the audit contracts that
  consume its output: analytics.coverage-gap-observation,
  analytics.missingness-observation, analytics.duplicate-observation).

  This contract answers one auditable question only: **does the stored
  observation still follow from its raw evidence when the admission pipeline
  is replayed over those signals?** It is a reproducibility check, not a
  re-measurement and not a score:

    * the replay is DETERMINISTIC and additive — each admitted signal
      contributes exactly 1 to its dimension, exactly as
      analytics.impact-observation derives tallies; corrections and
      retractions are tallied and never netted against anything;
    * a mismatch is a FINDING (a lineage break), enumerated per compared
      surface (:tallies :admitted-count :derived-flags) — never silently
      reconciled, never averaged, never corrected in place. The stored
      observation is carried VERBATIM in the lineage record; this contract
      never rewrites history;
    * the replay consumes the raw signals' structural admission surface
      (source class, dimension, provenance, window) and nothing else —
      re-valuing a signal's payload fields that admission does not read
      leaves the lineage byte-identical (fixture-proven), so the audit
      cannot become a back door for re-interpreting evidence;
    * identity is fixed: the stored observation's subject/window must equal
      the replay's declared subject/window or the whole audit is refused as
      :identity-mismatch rather than merged;
    * coverage is always :partial — lineage inside the supplied signals says
      nothing about evidence outside them, and a match is reproducibility,
      not truth: a replay that reproduces an observation has not validated
      the observation's claims about the world;
    * no ranking, no causal claim, no derived narrative.

  Self-contained by design: no dependency on any other analytics namespace.
  The local admission rule mirrors analytics.impact-observation/admit-signal
  decision-for-decision; the mirror is tested against the shape documented
  there. Pure functions only: no network, no clock, no file I/O. Determinism
  is a test fixture (byte-identical pr-str across runs).")

;; ---------------------------------------------------------------------------
;; Identity — subject + window (the replay-declared pair). Byte-identical
;; semantics to the other observation contracts, kept private/local.
;; ---------------------------------------------------------------------------

(defn- replay-identity-key
  [subject window]
  (pr-str {:subject subject
           :window {:from (:from window) :to (:to window)}}))

;; ---------------------------------------------------------------------------
;; Local admission mirror — decision-for-decision mirror of
;; analytics.impact-observation/admit-signal (research-scope.edn :impact-allow
;; / :forbid; same refusal reasons, same precedence, same window exclusion
;; semantics). Kept local so this module loads and audits on its own.
;; ---------------------------------------------------------------------------

(def ^:private impact-allow
  #{:citation-registry :official-policy-document :official-patent-record
    :standards-body-first-party :guideline-publisher-first-party
    :publisher-correction-or-retraction})

(def ^:private forbidden-classes
  #{:search-snippet :generated-summary :third-party-wiki-prose :scraped-profile
    :paywall-bypass :captcha-bypass :inferred-sponsorship :inferred-causality})

(def ^:private dimensions
  #{:scholarly-citation :replication :correction :retraction :policy-citation
    :patent-citation :standard-adoption :clinical-guideline-citation
    :dataset-or-software-reuse})

(def ^:private per-dimension-fields
  {:scholarly-citation            :tally/scholarly-citation
   :replication                   :tally/replication
   :correction                    :tally/correction
   :retraction                    :tally/retraction
   :policy-citation               :tally/policy-citation
   :patent-citation               :tally/patent-citation
   :standard-adoption             :tally/standard-adoption
   :clinical-guideline-citation   :tally/clinical-guideline-citation
   :dataset-or-software-reuse     :tally/dataset-or-software-reuse})

(defn- required-provenance-present? [s]
  (and (seq (:source-url s))
       (integer? (:observed-at s))
       (:content-hash s)))

(defn- out-of-window? [window s]
  (or (< (:observed-at s) (:from window))
      (> (:observed-at s) (:to window))))

(defn- admit-signal
  "Local mirror of analytics.impact-observation/admit-signal: same refusal
  reasons in the same precedence, same out-of-window exclusion."
  [signal window]
  (let [source-class (:source/source-class signal)
        observed-at (:observed-at signal)
        {:keys [from to]} window]
    (cond
      (not (map? signal))
      [:rejected :malformed-signal]
      (or (nil? source-class) (not (keyword? source-class)))
      [:rejected :missing-source-class]
      (contains? forbidden-classes source-class)
      [:rejected :forbidden-source-class]
      (not (contains? impact-allow source-class))
      [:rejected :source-class-not-impact-allow]
      (not (contains? dimensions (:dimension signal)))
      [:rejected :unknown-dimension]
      (not (required-provenance-present? signal))
      [:rejected :missing-provenance]
      (or (not (integer? from)) (not (integer? to)) (<= to from))
      [:rejected :malformed-window]
      (or (< observed-at from) (> observed-at to))
      [:rejected :out-of-window]
      :else [:admitted signal])))

(defn- partition-signals
  "Replay partition: {:admitted [...] :excluded-out-of-window [...]
  :rejected [...reasons...]} — mirrors the admission pipeline's partition."
  [signals window]
  (reduce (fn [acc s]
            (let [[verdict v] (admit-signal s window)]
              (if (= :admitted verdict)
                (update acc :admitted conj v)
                (if (and (map? s) (integer? (:observed-at s))
                         (out-of-window? window s))
                  (update acc :excluded-out-of-window conj s)
                  (update acc :rejected conj v)))))
          {:admitted [] :excluded-out-of-window [] :rejected []}
          signals))

;; ---------------------------------------------------------------------------
;; Replay derivation — additive tallies + derived flags over admitted only
;; ---------------------------------------------------------------------------

(defn- derive-replay-tallies
  "Additive per-dimension tallies over admitted signals. One admitted signal
  contributes exactly 1 to its dimension. No polarity netting: :correction
  and :retraction tallies stand on their own and are never subtracted."
  [admitted]
  (reduce (fn [m s]
            (update m (per-dimension-fields (:dimension s)) (fnil inc 0)))
          {}
          admitted))

(defn- single-source-dependency? [admitted]
  (and (seq admitted)
       (= 1 (count (group-by :source/source-class admitted)))
       (>= (count admitted) 2)))

(defn- has-content-hash-dupes? [admitted]
  (let [hashes (remove nil? (map :content-hash admitted))]
    (not= (count hashes) (count (distinct hashes)))))

(defn- derive-replay-flags
  "Only the stored flags that are PURE FUNCTIONS of the admitted set are
  recomputed. Flags that depend on the caller's full batch shape (excluded /
  rejected audit trails) are out of replay scope — the lineage record notes
  which surfaces were compared."
  [admitted]
  {:missing-is-unmeasured true
   :retraction-present (boolean (some #(= :retraction (:dimension %)) admitted))
   :correction-present (boolean (some #(= :correction (:dimension %)) admitted))
   :single-source-dependency (single-source-dependency? admitted)
   :duplicate-content-hash (has-content-hash-dupes? admitted)
   :coverage :partial})

(defn- conformant-stored-observation? [o]
  (and (map? o)
       (= "influence-observation" (:contract o))
       (= "v1" (:version o))
       (map? (:window o))
       (integer? (:from (:window o)))
       (integer? (:to (:window o)))
       (<= (:from (:window o)) (:to (:window o)))
       (seq (:method-version o))
       (map? (:subject o))
       (map? (:tallies o))
       (integer? (:admitted-count o))
       (map? (:flags o))
       (nil? (:ranking o))
       (true? (:ranking-forbidden o))
       (true? (:causal-claims-forbidden o))
       (sequential? (:claims o))))

;; ---------------------------------------------------------------------------
;; Lineage comparison — additive finding enumeration, never reconciliation
;; ---------------------------------------------------------------------------

(defn- compare-tallies [expected stored]
  (let [e (into (sorted-map) expected)
        s (into (sorted-map) stored)]
    (merge {:surface :tallies :match (= e s)}
           (when-not (= e s) {:expected e :stored s}))))

(defn- compare-derived-flags [expected stored]
  (let [surfaces (sort (keys expected))
        mismatches (into [] (remove (fn [k] (= (get expected k) (get stored k)))
                                    surfaces))]
    (if (empty? mismatches)
      {:surface :derived-flags :match true
       :compared surfaces}
      {:surface :derived-flags :match false
       :compared surfaces
       :mismatched-keys mismatches})))

;; ---------------------------------------------------------------------------
;; Validation of the audit inputs
;; ---------------------------------------------------------------------------

(defn- signals-conformant? [signals]
  (and (vector? signals) (every? #(or (map? %) (nil? %)) signals)))

;; ---------------------------------------------------------------------------
;; Build the lineage record
;; ---------------------------------------------------------------------------

(defn build-lineage-observation
  "Replay `signals` (the raw evidence vector) through the local admission
  mirror and compare the result against `stored-observation` (one
  influence-observation/v1 map).

  `replay-window` and `replay-subject` are the identity the replay is
  declared under; the stored observation must match both or the whole audit
  is refused as [:rejected :identity-mismatch]. `method-version` (the
  lineage audit's own method version) is caller-supplied and stamped
  verbatim.

  Returns [:accepted lineage-record] or [:rejected reason]. A lineage record
  NEVER rewrites the stored observation — it travels verbatim under
  :stored-observation. A match means reproduced, not validated: replaying
  the pipeline says nothing about whether the observation's claims about the
  world are true (:replay-is-not-validation is hardwired)."
  [method-version replay-window replay-subject signals stored-observation]
  (cond
    (not (vector? signals))
    [:rejected :signals-not-vector]

    (empty? signals)
    [:rejected :empty-signals]

    (not (seq method-version))
    [:rejected :missing-method-version]

    (not (map? replay-window))
    [:rejected :malformed-replay-window]

    (or (not (integer? (:from replay-window)))
        (not (integer? (:to replay-window)))
        (<= (:to replay-window) (:from replay-window)))
    [:rejected :malformed-replay-window]

    (not (map? replay-subject))
    [:rejected :malformed-replay-subject]

    (not (conformant-stored-observation? stored-observation))
    [:rejected :non-conformant-stored-observation]

    (not (signals-conformant? signals))
    [:rejected :malformed-signal-in-batch]

    :else
    (let [stored-window {:from (:from (:window stored-observation))
                         :to (:to (:window stored-observation))}]
      (if (not= (replay-identity-key replay-subject replay-window)
                (replay-identity-key (:subject stored-observation)
                                     stored-window))
        [:rejected :identity-mismatch]
        (let [partition (partition-signals signals replay-window)
              admitted (:admitted partition)
              expected-tallies (derive-replay-tallies admitted)
              expected-flags (derive-replay-flags admitted)
              tally-cmp (compare-tallies expected-tallies
                                         (:tallies stored-observation))
              count-cmp {:surface :admitted-count
                         :match (= (count admitted)
                                   (long (:admitted-count stored-observation)))
                         :expected (count admitted)
                         :stored (:admitted-count stored-observation)}
              flag-cmp (compare-derived-flags
                        expected-flags (:flags stored-observation))
              surfaces [tally-cmp count-cmp flag-cmp]
              mismatched (into [] (map :surface
                                       (remove :match surfaces)))]
          [:accepted
           {:contract "evidence-lineage-observation"
            :version "v1"
            :method-version method-version
            :subject replay-subject
            :window {:from (:from replay-window) :to (:to replay-window)}
            :stored-method-version (:method-version stored-observation)
            :stored-observation stored-observation
            :replay {:signal-count (count signals)
                     :admitted-count (count admitted)
                     :excluded-out-of-window-count
                     (count (:excluded-out-of-window partition))
                     :rejected-reason-counts
                     (into (sorted-map) (frequencies (:rejected partition)))}
            :lineage {:surfaces-compared (into [] (map :surface surfaces))
                      :all-match (empty? mismatched)
                      :mismatched-surfaces mismatched
                      :details surfaces}
            :flags {:missing-is-unmeasured true
                    :lineage-match (empty? mismatched)
                    :stored-observation-carried-verbatim true
                    :out-of-replay-surface :caller-batch-audit-trails
                    :coverage :partial}
            :replay-is-not-validation true
            :ranking nil
            :ranking-forbidden true
            :causal-claims-forbidden true
            :claims []}])))))

;; ---------------------------------------------------------------------------
;; Append-only refresh history
;; ---------------------------------------------------------------------------

(defn refresh
  "Append a new lineage record onto the history of lineage audits. Prior
  records are immutable; nothing is rewritten."
  [history lineage]
  (conj (vec history) lineage))

(defn history-records
  "The append-only list of prior lineage records."
  [history]
  (vec history))

;; ---------------------------------------------------------------------------
;; Hyakka proposal / readback
;; ---------------------------------------------------------------------------

(defn dedupe-key
  "Deterministic identity for one lineage audit. Sorted maps so iteration
  order never leaks into the key. Pure string — no crypto, no clock."
  [lineage]
  (when (map? lineage)
    (str "evidence-lineage-observation/v1:"
         (pr-str {:subject (:subject lineage)
                  :window (:window lineage)
                  :method-version (:method-version lineage)
                  :stored-method-version (:stored-method-version lineage)
                  :lineage (into (sorted-map)
                                 (select-keys (:lineage lineage)
                                              [:all-match
                                               :mismatched-surfaces
                                               :surfaces-compared]))
                  :replay (into (sorted-map)
                                (select-keys (:replay lineage)
                                             [:signal-count
                                              :admitted-count
                                              :excluded-out-of-window-count]))}))))

(defn hyakka-proposal
  "Proposal payload for the Hyakka wiki, or nil when nothing was replayed —
  an audit over zero signals measures nothing and proposing a record for it
  would dress absence up as data."
  [lineage]
  (when (and (map? lineage)
             (pos? (long (or (get-in lineage [:replay :signal-count]) 0))))
    {:proposal/type :evidence-lineage-observation
     :proposal/dedupe-key (dedupe-key lineage)
     :proposal/contract "evidence-lineage-observation/v1"
     :proposal/method-version (:method-version lineage)
     :proposal/subject (:subject lineage)
     :proposal/window (:window lineage)
     :proposal/stored-method-version (:stored-method-version lineage)
     :proposal/replay (:replay lineage)
     :proposal/lineage (:lineage lineage)
     :proposal/flags (:flags lineage)
     :proposal/coverage :partial
     :proposal/replay-is-not-validation true
     :proposal/ranking nil
     :proposal/ranking-forbidden true
     :proposal/causal-claims-forbidden true
     :proposal/claims []
     :proposal/note
     "replay-reproducibility-audit-not-impact-verdict-mismatch-is-a-finding"}))

(defn hyakka-readback-accept?
  "Accept a readback only if it is still the record this actor proposed: same
  contract, same dedupe-key, same replay counts, same lineage findings — and
  the structural refusals (no ranking, no causal claims, replay-is-not-
  validation) not stripped. A readback whose lineage verdict was flipped
  (a break reported as a match, or vice versa) is NOT the proposal this
  actor made; refuse. Anything else is tampering; refuse."
  [proposal readback]
  (and (map? readback)
       (= "evidence-lineage-observation/v1" (:proposal/contract readback))
       (some? (:proposal/dedupe-key readback))
       (= (:proposal/dedupe-key proposal) (:proposal/dedupe-key readback))
       (= (:proposal/method-version proposal) (:proposal/method-version readback))
       (= (:proposal/subject proposal) (:proposal/subject readback))
       (= (:proposal/window proposal) (:proposal/window readback))
       (= (:proposal/stored-method-version proposal)
          (:proposal/stored-method-version readback))
       (= (:proposal/replay proposal) (:proposal/replay readback))
       (= (:proposal/lineage proposal) (:proposal/lineage readback))
       (= (:proposal/flags proposal) (:proposal/flags readback))
       (some? (:proposal/flags readback))
       (contains? (:proposal/flags readback) :missing-is-unmeasured)
       (= :partial (:proposal/coverage readback))
       (true? (:proposal/replay-is-not-validation readback))
       (true? (:proposal/ranking-forbidden readback))
       (true? (:proposal/causal-claims-forbidden readback))
       (nil? (:proposal/ranking readback))
       (empty? (:proposal/claims readback))))
