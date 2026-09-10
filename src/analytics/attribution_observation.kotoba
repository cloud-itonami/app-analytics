(ns analytics.attribution-observation
  "attribution-observation/v1 — bounded, provenance-preserving audit of
  FIRST-PARTY ATTRIBUTION for the cloud-itonami analytics actor.

  This contract answers exactly one auditable question: **for admitted
  signals that claim a first-party source (:official-policy-document,
  :official-patent-record, :standards-body-first-party,
  :guideline-publisher-first-party), does the signal's source URL resolve
  against a caller-declared authority domain for that source class — and
  which claims carry no such declaration?**

  What this contract is NOT:

    * it does NOT verify truth. A verified attribution says the URL sits
      under an authority domain the caller declared — nothing more.
      Citation is not support; attribution match is not correctness;
    * it does NOT fuzzy-match. Host comparison is exact registrable-domain
      match (dot-boundary suffix only, never substring) against the
      caller-supplied authority map. A host that cannot be parsed is
      reported, never coerced into a match;
    * it does NOT touch third-party classes (:citation-registry,
      :publisher-correction-or-retraction). Those signals are enumerated
      :not-applicable, whole and unmodified — a caller cannot smuggle a
      first-party verdict onto them by widening the map;
    * a first-party signal checked with NO declared domain for its class is
      :authority-undeclared-class — flagged, never averaged away. Absence
      of declarations is NOT evidence of soundness
      (:no-authority-map-is-not-sound);
    * coverage is always :partial — the audit says nothing about artifacts
      outside the supplied batch.

  Structural refusals (no ranking, no severity score, no causal claims, no
  people-level inference) are stamped into every record. Pure functions
  only: no network, no clock, no file I/O. Determinism is a test fixture
  (byte-identical pr-str across runs)."
  (:require [kotoba.lang.text :as str]))

;; ---------------------------------------------------------------------------
;; Source classes — first-party classes are auditable here; third-party
;; classes are enumerated :not-applicable and never attributed.
;; ---------------------------------------------------------------------------

(def ^:private first-party-source-classes
  #{:official-policy-document :official-patent-record
    :standards-body-first-party :guideline-publisher-first-party})

(def ^:private known-third-party-source-classes
  #{:citation-registry :publisher-correction-or-retraction})

(def ^:private known-dimensions
  #{:scholarly-citation :replication :correction :retraction :policy-citation
    :patent-citation :standard-adoption :clinical-guideline-citation
    :dataset-or-software-reuse})

;; ---------------------------------------------------------------------------
;; helpers — exact host extraction, exact suffix match only
;; ---------------------------------------------------------------------------

(defn- url-host
  "Extract the lowercase host from a URL string: the segment between the
  first \"://\" and the first \"/\", \"?\", \"#\" or \"@\" boundary. No
  network, no JS URL object (this is `.cljc` and must run identically on
  JVM and cljs). Returns nil on anything that does not parse — parsing
  failure is reported as a mismatch, never coerced into a match."
  [s]
  (when (seq s)
    (some-> (re-find #"://([^/?#@]+)" s) second str/lower not-empty)))

(defn- authority-declared?
  "True when host equals a declared authority domain exactly, or is a
  subdomain of one (dot-boundary suffix match only — never substring).
  An unparseable host (nil) is never a match."
  [host declared]
  (boolean
   (and host
        (some (fn [d]
                (or (= host d)
                    (str/ends-with? host (str "." d))))
              declared))))

;; ---------------------------------------------------------------------------
;; validation
;; ---------------------------------------------------------------------------

(defn- conformant-signal? [s]
  (and (map? s)
       (contains? known-dimensions (:dimension s))
       (seq (:source-url s))
       (integer? (:observed-at s))
       (contains? (into #{} cat [first-party-source-classes
                                 known-third-party-source-classes])
                  (:source/source-class s))))

;; ---------------------------------------------------------------------------
;; derivation
;; ---------------------------------------------------------------------------

(def ^:private verdicts #{:authority-declared :authority-mismatch
                          :authority-undeclared-class})

(defn- classify
  "Bucket one first-party signal against the caller-declared authority map.
  A third-party signal never reaches this function."
  [authority sig]
  (let [declared (get authority (:source/source-class sig))]
    (cond
      (or (nil? declared) (empty? declared))
      :authority-undeclared-class

      (authority-declared? (url-host (:source-url sig)) declared)
      :authority-declared

      :else :authority-mismatch)))

(defn- summarize
  "Verbatim minimal projection of one signal — the observation carries the
  fact, never a judgement about people or organizations."
  [s]
  {:source-url (:source-url s) :observed-at (:observed-at s)})

(defn build-observation
  "Build one attribution-observation/v1 record.

  `authority` maps a first-party source class to the set of registrable
  domains the caller declares authoritative for that class. An empty or
  missing map is :no-authority-map-configured — a missingness flag, never
  a measurement of soundness.

  Returns [:accepted record] or [:rejected reason]."
  [method-version window authority admitted]
  (cond
    (not (vector? admitted))
    [:rejected :admitted-not-vector]

    (not (map? authority))
    [:rejected :authority-not-a-map]

    (or (not (map? window))
        (not (integer? (:from window)))
        (not (integer? (:to window)))
        (<= (:to window) (:from window)))
    [:rejected :malformed-window]

    (some (complement conformant-signal?) admitted)
    [:rejected :non-conformant-signal-in-batch]

    :else
    (let [partitioned (group-by (fn [s]
                                  (if (contains? first-party-source-classes
                                                 (:source/source-class s))
                                    :first-party :not-applicable))
                                admitted)
          first-party (sort-by (juxt :source-url :observed-at)
                               (get partitioned :first-party []))
          third-party (sort-by (juxt :source-url :observed-at)
                               (get partitioned :not-applicable []))
          buckets (group-by (partial classify authority) first-party)
          ;; verdict counts are tallied ADDITIVELY, never combined into a
          ;; single "attribution quality" number
          counts (into (sorted-map)
                       (map (fn [v] [v (count (get buckets v))]))
                       (sort verdicts))
          flags {:missing-is-unmeasured true
                 :no-authority-map-configured (empty? authority)
                 :no-authority-map-is-not-sound true
                 :authority-class-counts counts
                 :not-applicable-count (count third-party)
                 :coverage :partial}]
      [:accepted
       {:contract "attribution-observation"
        :version "v1"
        :method-version method-version
        :window window
        :admitted-count (count admitted)
        :authority-source-class-count (count authority)
        :authority-declared-signals (mapv summarize (get buckets :authority-declared))
        :authority-mismatch-signals (mapv summarize (get buckets :authority-mismatch))
        :authority-undeclared-class-signals
        (mapv summarize (get buckets :authority-undeclared-class))
        :not-applicable-signals (mapv summarize third-party)
        :flags flags
        :severity-score nil
        :severity-score-forbidden true
        :trust-ranking nil
        :trust-ranking-forbidden true
        :causal-claims-forbidden true
        :ranking nil
        :ranking-forbidden true
        :claims []}])))

;; ---------------------------------------------------------------------------
;; append-only refresh history
;; ---------------------------------------------------------------------------

(defn refresh
  "Append a new attribution record onto prior history. Nothing is rewritten."
  [history obs]
  (conj (vec history) obs))

(defn history-records
  "The append-only list of prior attribution records."
  [history]
  (vec history))

;; ---------------------------------------------------------------------------
;; Hyakka proposal / readback
;; ---------------------------------------------------------------------------

(defn dedupe-key
  "Deterministic identity for one attribution observation. Sorted maps so
  iteration order never leaks into the key. Pure string — no crypto, no
  clock."
  [obs]
  (when (map? obs)
    (str "attribution-observation/v1:"
         (pr-str {:window (:window obs)
                  :method-version (:method-version obs)
                  :authority-source-class-count (:authority-source-class-count obs)
                  :admitted-count (:admitted-count obs)
                  :authority-declared-signals (:authority-declared-signals obs)
                  :authority-mismatch-signals (:authority-mismatch-signals obs)}))))

(defn hyakka-proposal
  "Proposal payload for the Hyakka wiki. Unlike contracts that need a
  matched event, an attribution audit with zero declarations configured but
  signals admitted IS proposed — 'N first-party claims rest on no declared
  authority' is itself a measurable fact. Only a batch with nothing
  admitted measures nothing."
  [obs]
  (when (and (map? obs) (pos? (long (or (:admitted-count obs) 0))))
    {:proposal/type :attribution-observation
     :proposal/dedupe-key (dedupe-key obs)
     :proposal/contract "attribution-observation/v1"
     :proposal/method-version (:method-version obs)
     :proposal/window (:window obs)
     :proposal/authority-source-class-count (:authority-source-class-count obs)
     :proposal/admitted-count (:admitted-count obs)
     :proposal/authority-declared-signals (:authority-declared-signals obs)
     :proposal/authority-mismatch-signals (:authority-mismatch-signals obs)
     :proposal/authority-undeclared-class-signals
     (:authority-undeclared-class-signals obs)
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
     :proposal/note "attribution-match-is-not-truth-absence-of-declarations-is-not-soundness-not-applicable-signals-kept"}))

(defn hyakka-readback-accept?
  "Accept a readback only if it is still the record this actor proposed: same
  contract, same dedupe-key, same method-version, same verdict buckets, same
  flags — and the structural refusals (no severity score, no trust ranking,
  no causal claims) not stripped, and the not-applicable signals not
  silently deleted. Anything else is tampering; refuse."
  [proposal readback]
  (and (map? readback)
       (= "attribution-observation/v1" (:proposal/contract readback))
       (some? (:proposal/dedupe-key readback))
       (= (:proposal/dedupe-key proposal) (:proposal/dedupe-key readback))
       (= (:proposal/method-version proposal) (:proposal/method-version readback))
       (= (:proposal/window proposal) (:proposal/window readback))
       (= (:proposal/admitted-count proposal) (:proposal/admitted-count readback))
       (= (:proposal/authority-declared-signals proposal)
          (:proposal/authority-declared-signals readback))
       (= (:proposal/authority-mismatch-signals proposal)
          (:proposal/authority-mismatch-signals readback))
       (= (:proposal/authority-undeclared-class-signals proposal)
          (:proposal/authority-undeclared-class-signals readback))
       (= (:proposal/flags proposal) (:proposal/flags readback))
       (some? (:proposal/flags readback))
       (contains? (:proposal/flags readback) :missing-is-unmeasured)
       (contains? (:proposal/flags readback) :no-authority-map-is-not-sound)
       (= :partial (:proposal/coverage readback))
       (nil? (:proposal/severity-score readback))
       (true? (:proposal/severity-score-forbidden readback))
       (nil? (:proposal/trust-ranking readback))
       (true? (:proposal/trust-ranking-forbidden readback))
       (nil? (:proposal/ranking readback))
       (true? (:proposal/ranking-forbidden readback))
       (true? (:proposal/causal-claims-forbidden readback))
       (empty? (:proposal/claims readback))))

;; ---------------------------------------------------------------------------
;; Public readback surface — /observations/attribution
;; ---------------------------------------------------------------------------

(defn- minimal-shape-ok? [obs]
  (and (map? obs)
       (= "attribution-observation" (:contract obs))
       (= "v1" (:version obs))
       (map? (:window obs))
       (integer? (:admitted-count obs))
       (sequential? (:authority-mismatch-signals obs))))

(defn configure-observation
  "The pure decision behind the `GET /observations/attribution` readback
  surface. Same shape as retraction/configure-observation: the Worker has
  no store of its own — the latest record reaches the edge the same way
  every other setting does, the deploy-time `ATTRIBUTION_OBSERVATION_JSON`
  env, already JSON-parsed by the caller.

    [:not-configured {:note …}] — serve 404; absence of a configured record
      is not a measurement and never becomes zero.
    [:invalid :reason] — something is configured but it is not an
      attribution-observation/v1 record. Serve 502 rather than guessing.
    [:ok obs] — serve the record verbatim, flags intact."
  [parsed]
  (cond
    (nil? parsed)
    [:not-configured
     {:note "no attribution observation is configured on this deploy; absence of a configured record is not a measurement"}]

    (not (map? parsed))
    [:invalid :not-a-json-object]

    (not (minimal-shape-ok? parsed))
    [:invalid :not-a-attribution-observation/v1-record]

    :else [:ok parsed]))
