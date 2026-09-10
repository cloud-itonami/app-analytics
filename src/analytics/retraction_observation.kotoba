(ns analytics.retraction-observation
  "retraction-observation/v1 — bounded, provenance-preserving audit of
  RETRACTION and NEGATIVE EVIDENCE, for the cloud-itonami analytics actor.

  Prior contracts observe signals inside one window (influence-observation/v1),
  audit where those signals came from (provenance-diversity-observation/v1),
  and audit the measurement cadence itself (window-refresh-observation/v1).
  None of them preserve the other half of the evidence record: when an
  admitted signal has been retracted, corrected, or contradicted by its
  publisher. This contract closes that hole. It answers exactly one
  auditable question: **which admitted signals are affected by a caller-
  supplied retraction/correction notice, and which dimensions rest on
  signals with no notice either way?**

    * notices are matched to admitted signals ONLY by exact content hash or
      exact source URL. No fuzzy title matching, no author inference, no
      similarity heuristic — a wrong retraction match is worse than none;
    * a signal with a matched notice is FLAGGED (:retracted-signal) and kept
      verbatim in the observation. It is never deleted, reweighted, or
      averaged out — preserving retractions is the whole point, and silently
      dropping them would manufacture clean data out of dirty evidence;
    * a retraction is a fact ABOUT the artifact, not a verdict about people.
      No researcher, author, or organization quality is derived. Ranking a
      person by retraction count is exactly the unsupported inference this
      contract structurally forbids (retraction is not reputation; funding
      is not endorsement);
    * ABSENCE of notices is :no-notices-configured — it is NOT \"no
      retractions\" and NOT \"all signals sound\". Missingness stays missing;
    * the notice stream is caller-supplied and re-validated here (source
      class must be :publisher-correction-or-retraction, same allow set as
      influence-observation/v1 admission) so a batch of self-declared
      retractions cannot reach the derivation silently;
    * a signal whose notice is OUT OF the notice-as-of scope is
      :notice-outside-scope — flagged, never silently treated as either
      retracted or sound;
    * coverage is always :partial — the audit says nothing about artifacts
      outside the supplied batch and notice stream.

  Pure functions only: no network, no clock, no file I/O. Determinism is a
  test fixture (byte-identical pr-str across runs).")

;; ---------------------------------------------------------------------------
;; Dimension set / allow set — mirrors analytics.impact-observation
;; ---------------------------------------------------------------------------

(def known-dimensions
  #{:scholarly-citation :replication :correction :retraction :policy-citation
    :patent-citation :standard-adoption :clinical-guideline-citation
    :dataset-or-software-reuse})

(def allowed-notice-source-classes
  #{:publisher-correction-or-retraction})

;; ---------------------------------------------------------------------------
;; Notice validation — re-validate the minimum notice shape locally
;; ---------------------------------------------------------------------------

(defn- conformant-notice? [n]
  (and (map? n)
       (contains? allowed-notice-source-classes (:source/source-class n))
       (seq (:source-url n))
       (integer? (:observed-at n))
       (contains? #{:retraction :correction :withdrawal} (:notice/kind n))
       (or (seq (:content-hash n)) (seq (:notice/target-url n)))))

(defn- conformant-signal? [s]
  (and (map? s)
       (contains? known-dimensions (:dimension s))
       (seq (:source-url s))
       (integer? (:observed-at s))
       (:content-hash s)
       (contains? #{:citation-registry :official-policy-document
                    :official-patent-record :standards-body-first-party
                    :guideline-publisher-first-party
                    :publisher-correction-or-retraction}
                  (:source/source-class s))))

;; ---------------------------------------------------------------------------
;; Matching — exact identity only
;; ---------------------------------------------------------------------------

(defn- notice-matches-signal? [n s]
  (or (and (seq (:content-hash n))
           (= (:content-hash n) (:content-hash s)))
      (and (seq (:notice/target-url n))
           (= (:notice/target-url s) (:notice/target-url n)))))

(defn match-notices
  "Partition admitted signals by exact-match against the validated notice
  stream. Every bucket is enumerated whole; nothing is dropped here.

    :retracted   — matched (hash or URL) by at least one notice
    :unmatched   — no notice matched; NOT evidence of soundness
    :out-of-scope — matched, but the notice's observed-at is outside the
                    observation window [from, to)"
  [admitted notices {:keys [from to]}]
  (let [affected (fn [s]
                   (some (fn [n] (when (notice-matches-signal? n s) n)) notices))
        in-scope? (fn [n] (and (<= from (:observed-at n)) (< (:observed-at n) to)))
        ;; buckets are sorted by content-hash so batch order never leaks
        ;; into the record (determinism is a fixture, not a hope)
        hash-of (juxt :content-hash :source-url)]
    {:retracted    (sort-by hash-of
                            (keep (fn [s]
                                    (when-let [n (affected s)]
                                      (when (in-scope? n) s)))
                                  admitted))
     :out-of-scope (sort-by hash-of
                            (keep (fn [s]
                                    (when-let [n (affected s)]
                                      (when-not (in-scope? n)
                                        ;; report the NOTICE's observed-at —
                                        ;; the whole point of out-of-scope is
                                        ;; that the retraction was observed
                                        ;; outside the measured window
                                        (assoc s :observed-at (:observed-at n)))))
                                  admitted))
     :unmatched    (sort-by hash-of (remove affected admitted))}))

;; ---------------------------------------------------------------------------
;; Derived observation — additive facts + flags only
;; ---------------------------------------------------------------------------

(defn derive-retraction-exposure
  "Per-dimension exposure: which dimensions contain at least one flagged
  signal. Presence only — never a rate, never a score."
  [signals]
  (into (sorted-map)
        (frequencies (map :dimension signals))))

(defn- derive-flags
  [admitted notices {:keys [retracted out-of-scope unmatched]} partitioned]
  (let [{:keys [rejected-notices]} partitioned]
    {:missing-is-unmeasured true
     :no-notices-configured (empty? notices)
     :no-notices-is-not-sound true
     :unrecognized-notice-count (count rejected-notices)
     :unrecognized-notices
     (mapv (fn [n] {:source-class (:source/source-class n)}) rejected-notices)
     :retracted-signal-count (count retracted)
     :notice-outside-scope-count (count out-of-scope)
     :retraction-exposure (derive-retraction-exposure retracted)
     :dimensions-observed (sort (into [] (distinct (map :dimension admitted))))
     :coverage :partial}))

(defn- retraction-observation-record
  [method-version window subject admitted notices match flags]
  {:contract "retraction-observation"
   :version "v1"
   :method-version method-version
   :subject subject
   :window window
   :admitted-count (count admitted)
   :notice-count (count notices)
   :retracted-signals (mapv (fn [s] {:content-hash (:content-hash s)
                                     :dimension (:dimension s)
                                     :observed-at (:observed-at s)
                                     :source-class (:source/source-class s)})
                            (:retracted match))
   :notice-outside-scope-signals
   (mapv (fn [s] {:content-hash (:content-hash s)
                  :observed-at (:observed-at s)})
         (:out-of-scope match))
   :flags flags
   :severity-score nil
   :severity-score-forbidden true
   :trust-ranking nil
   :trust-ranking-forbidden true
   :causal-claims-forbidden true
   :ranking nil
   :ranking-forbidden true
   :claims []})

(defn build-observation
  "Build one retraction-observation/v1 record.

  `method-version` is caller-supplied and stamped verbatim. `admitted`
  arrives pre-partitioned from analytics.impact-observation/partition-signals
  and is re-validated here. `notices` are caller-supplied retraction /
  correction / withdrawal notices; every one that fails the conformant
  notice shape is enumerated in :flags/:unrecognized-notices — never
  averaged in, never dropped. The hard boundaries are structural: exact
  matching only, affected signals kept whole, no severity score, no trust
  ranking, no inference about people."
  [method-version window admitted notices subject]
  (let [{:keys [from to]} window
        [valid-notices rejected-notices]
        ((juxt filter remove) conformant-notice? (vec notices))]
    (cond
      (not (vector? admitted))
      [:rejected :admitted-not-vector]
      (not (or (nil? notices) (sequential? notices)))
      [:rejected :notices-not-sequential]
      (or (not (integer? from)) (not (integer? to)) (<= to from))
      [:rejected :malformed-window]
      (some (complement conformant-signal?) admitted)
      [:rejected :non-conformant-signal-in-batch]
      :else
      (let [match (match-notices admitted valid-notices {:from from :to to})
            flags (derive-flags admitted valid-notices match
                                {:rejected-notices rejected-notices})]
        [:accepted (retraction-observation-record
                    method-version window subject admitted valid-notices
                    match flags)]))))

;; ---------------------------------------------------------------------------
;; Append-only refresh history
;; ---------------------------------------------------------------------------

(defn refresh
  "Append a new retraction observation onto the history of records. Prior
  records are immutable; nothing is rewritten."
  [history obs]
  (conj (vec history) obs))

(defn history-records
  "The append-only list of prior retraction records."
  [history]
  (vec history))

;; ---------------------------------------------------------------------------
;; Hyakka proposal / readback
;; ---------------------------------------------------------------------------

(defn dedupe-key
  "Deterministic identity for one retraction audit. Sorted maps so iteration
  order never leaks into the key. Pure string — no crypto, no clock."
  [obs]
  (when (map? obs)
    (str "retraction-observation/v1:"
         (pr-str {:subject (:subject obs)
                  :window (:window obs)
                  :method-version (:method-version obs)
                  :admitted-count (:admitted-count obs)
                  :notice-count (:notice-count obs)
                  :retracted-signals (mapv #(into (sorted-map) %)
                                           (:retracted-signals obs))}))))

(defn hyakka-proposal
  "Proposal payload for the Hyakka wiki. Unlike the other contracts, an
  observation with zero matched notices but a configured notice stream IS
  proposed — 'no notice matched under exact matching, with N notices
  configured' is itself a measurable fact. Only a batch with no notice
  stream configured and nothing admitted measures nothing."
  [obs]
  (when (and (map? obs)
             (or (pos? (long (or (:notice-count obs) 0)))
                 (pos? (long (or (:admitted-count obs) 0)))))
    {:proposal/type :retraction-observation
     :proposal/dedupe-key (dedupe-key obs)
     :proposal/contract "retraction-observation/v1"
     :proposal/method-version (:method-version obs)
     :proposal/subject (:subject obs)
     :proposal/window (:window obs)
     :proposal/notice-count (:notice-count obs)
     :proposal/retracted-signals (:retracted-signals obs)
     :proposal/notice-outside-scope-signals (:notice-outside-scope-signals obs)
     :proposal/flags (:flags obs)
     :proposal/coverage :partial
     :proposal/severity-score nil
     :proposal/severity-score-forbidden true
     :proposal/trust-ranking nil
     :proposal/trust-ranking-forbidden true
     :proposal/ranking nil
     :proposal/ranking-forbidden true
     :proposal/causal-claims-forbidden true
     :proposal/claims []
     :proposal/note "retraction-preservation-not-truth-verdict-affected-signals-kept-not-deleted-absence-of-notices-is-not-soundness"}))

(defn hyakka-readback-accept?
  "Accept a readback only if it is still the record this actor proposed: same
  contract, same dedupe-key, same method-version, same retracted signals and
  out-of-scope signals, same flags — and the structural refusals (no
  severity score, no trust ranking, no causal claims) not stripped, and the
  affected signals not silently deleted. Anything else is tampering; refuse."
  [proposal readback]
  (and (map? readback)
       (= "retraction-observation/v1" (:proposal/contract readback))
       (some? (:proposal/dedupe-key readback))
       (= (:proposal/dedupe-key proposal) (:proposal/dedupe-key readback))
       (= (:proposal/method-version proposal) (:proposal/method-version readback))
       (= (:proposal/subject proposal) (:proposal/subject readback))
       (= (:proposal/window proposal) (:proposal/window readback))
       (= (:proposal/notice-count proposal) (:proposal/notice-count readback))
       (= (:proposal/retracted-signals proposal) (:proposal/retracted-signals readback))
       (= (:proposal/notice-outside-scope-signals proposal)
          (:proposal/notice-outside-scope-signals readback))
       (= (:proposal/flags proposal) (:proposal/flags readback))
       (some? (:proposal/flags readback))
       (contains? (:proposal/flags readback) :missing-is-unmeasured)
       (contains? (:proposal/flags readback) :no-notices-is-not-sound)
       (= :partial (:proposal/coverage readback))
       (nil? (:proposal/severity-score readback))
       (true? (:proposal/severity-score-forbidden readback))
       (nil? (:proposal/trust-ranking readback))
       (true? (:proposal/trust-ranking-forbidden readback))
       (true? (:proposal/ranking-forbidden readback))
       (true? (:proposal/causal-claims-forbidden readback))
       (nil? (:proposal/ranking readback))
       (empty? (:proposal/claims readback))))

;; ---------------------------------------------------------------------------
;; Public readback surface — /observations/retraction
;; ---------------------------------------------------------------------------

(defn- minimal-shape-ok? [obs]
  (and (map? obs)
       (= "retraction-observation" (:contract obs))
       (= "v1" (:version obs))
       (map? (:window obs))
       (integer? (:notice-count obs))
       (sequential? (:retracted-signals obs))))

(defn configure-observation
  "The pure decision behind the `GET /observations/retraction` readback
  surface. Same shape as window-refresh/configure-observation: the Worker
  has no store of its own — the latest record reaches the edge the same way
  every other setting does, the deploy-time `RETRACTION_OBSERVATION_JSON`
  env, already JSON-parsed by the caller.

    [:not-configured {:note …}] — serve 404; absence of a configured record
      is not a measurement and never becomes zero.
    [:invalid :reason] — something is configured but it is not a
      retraction-observation/v1 record. Serve 502 rather than guessing.
    [:ok obs] — serve the record verbatim, flags intact."
  [parsed]
  (cond
    (nil? parsed)
    [:not-configured
     {:note "no retraction observation is configured on this deploy; absence of a configured record is not a measurement"}]

    (not (map? parsed))
    [:invalid :not-a-json-object]

    (not (minimal-shape-ok? parsed))
    [:invalid :not-a-retraction-observation/v1-record]

    :else [:ok parsed]))
