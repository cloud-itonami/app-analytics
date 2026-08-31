(ns analytics.impact-observation
  "influence-observation/v1 — bounded, provenance-preserving research influence
  observations for the cloud-itonami analytics actor.

  Scope (research-scope.edn, :impact-dimensions): signals arrive with mandatory
  provenance, are admitted only from :impact-allow source classes, and are
  reduced to ADDITIVE tallies inside an explicit measurement window. This is a
  versioned observation, not timeless impact:

    * citation is not support; attention is not impact; funding is not
      endorsement; correlation is not causation;
    * retractions and corrections are PRESERVED — polarities are never netted;
    * missing data is `missing-is-unmeasured`, never zero;
    * coverage is always :partial — never a completeness claim;
    * ranking of researchers and causal claims are structurally refused.

  Pure functions only: no network, no clock, no file I/O. Determinism is a test
  fixture (byte-identical pr-str across runs).")

;; ---------------------------------------------------------------------------
;; Source admission — research-scope.edn :impact-allow / :forbid
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

(defn admit-signal
  "Admit one raw signal into the pipeline, or reject it with a reason.

  Returns [:admitted signal] or [:rejected reason]. Rejections are for: missing
  provenance, non-allowed or explicitly forbidden source class, unknown
  dimension, or out-of-window observed-at. Out-of-window is an EXCLUSION,
  enumerated for audit — never silently dropped into a bucket."
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

(defn partition-signals
  "Admit a batch; returns {:admitted [...] :excluded-out-of-window [...]
  :rejected [...]}. Out-of-window signals are enumerated (kept whole) so an
  audit can see what the window excluded and why."
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
;; Derived observation — additive tallies only
;; ---------------------------------------------------------------------------

(defn derive-tallies
  "Additive per-dimension tallies over admitted signals. Each admitted signal
  contributes exactly 1 to its dimension (a signal is an observation event, not
  a weight). No polarity netting: :correction and :retraction tallies stand on
  their own and are never subtracted from anything."
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

(defn derive-flags
  "Uncertainty / coverage flags. Every observation carries them; absent data
  produces the flag, never a zero."
  [admitted excluded-out-of-window]
  {:missing-is-unmeasured true
   :excluded-signals-present (pos? (count excluded-out-of-window))
   :retraction-present (boolean (some #(= :retraction (:dimension %)) admitted))
   :correction-present (boolean (some #(= :correction (:dimension %)) admitted))
   :single-source-dependency (single-source-dependency? admitted)
   :duplicate-content-hash (has-content-hash-dupes? admitted)
   :coverage :partial})

(defn- flags-with-audit [admitted excluded-out-of-window rejected]
  (let [flags (derive-flags admitted excluded-out-of-window)
        excluded (mapv (fn [s]
                         {:observed-at (:observed-at s)
                          :source-class (:source/source-class s)})
                       excluded-out-of-window)]
    (assoc flags
           :excluded-out-of-window excluded
           :rejected-reasons (frequencies rejected))))

(defn build-observation
  "Build one influence-observation/v1 record.

  `method-version` is caller-supplied and stamped verbatim; changing the
  derivation means bumping it. The hard boundaries are structural: no ranking,
  no causal claim, no derived narrative — only additive tallies, flags, and the
  audit trail of what was excluded."
  [method-version partitioned window subject]
  (let [{:keys [from to]} window
        flags (flags-with-audit (:admitted partitioned)
                                (:excluded-out-of-window partitioned)
                                (:rejected partitioned))
        base {:contract "influence-observation"
              :version "v1"
              :method-version method-version
              :subject subject
              :window {:from from :to to}
              :tallies (derive-tallies (:admitted partitioned))
              :admitted-count (count (:admitted partitioned))
              :flags flags}]
    ;; structural refusals — never written by this contract
    (assoc base
           :ranking nil
           :ranking-forbidden true
           :causal-claims-forbidden true
           :claims [])))

;; ---------------------------------------------------------------------------
;; Append-only refresh history
;; ---------------------------------------------------------------------------

(defn refresh
  "Append a new observation for a (possibly different) window onto the history.
  Prior observations are immutable: nothing in `history` is rewritten. Returns
  the new history (vector, chronological)."
  [history obs]
  (conj (vec history) obs))

(defn history-observations
  "The append-only list of prior observations (never mutated by this module)."
  [history]
  (vec history))

;; ---------------------------------------------------------------------------
;; Hyakka proposal / readback
;; ---------------------------------------------------------------------------

(defn hyakka-proposal
  "Proposal payload for the Hyakka wiki (network-awai/app-hyakka), or nil when
  nothing was measured. Absence is not zero: with no admitted signals there is
  nothing to propose and this returns nil — no fabricated observation, no
  empty tallies dressed up as data."
  [obs]
  (when (pos? (long (or (:admitted-count obs) 0)))
    {:proposal/type :influence-observation
     :proposal/contract "influence-observation/v1"
     :proposal/method-version (:method-version obs)
     :proposal/subject (:subject obs)
     :proposal/window (:window obs)
     :proposal/tallies (:tallies obs)
     :proposal/flags (:flags obs)
     :proposal/coverage :partial
     :proposal/note "metric-is-a-versioned-observation-not-timeless-impact"}))

(defn hyakka-readback-accept?
  "Accept a readback only if it is still the observation this actor proposed:
  same contract, same method-version, same tallies, provenance fields intact,
  and the structural refusals (no ranking, no causal claims) not stripped.
  Anything else is tampering — refuse."
  [proposal readback]
  (and (map? readback)
       (= "influence-observation/v1" (:proposal/contract readback))
       (= (:proposal/method-version proposal) (:proposal/method-version readback))
       (= (:proposal/subject proposal) (:proposal/subject readback))
       (= (:proposal/window proposal) (:proposal/window readback))
       (= (:proposal/tallies proposal) (:proposal/tallies readback))
       (= (:proposal/flags proposal) (:proposal/flags readback))
       (some? (:proposal/flags readback))
       (every? #(contains? (:proposal/flags readback) %)
               [:missing-is-unmeasured :coverage])
       (= :partial (:proposal/coverage readback))))
