(ns analytics.denominator-observation
  "denominator-observation/v1 — bounded, provenance-preserving audit of
  DENOMINATOR DECLARATIONS on one derived tally, for the cloud-itonami
  analytics actor.

  influence-observation/v1 admits signals inside a measurement window and
  derives additive tallies per dimension. retraction-observation/v1 preserves
  negative evidence. signal-currency-observation/v1 makes the AGE of the
  admitted evidence observable. None of them make the DENOMINATOR of a tally
  observable — and a raw tally that silently rests on an unknown candidate
  base reads as comparable across fields, years, and registries when it is
  not: 50 citations over a registry that exposes 10,000 candidate works and
  50 citations over one that exposes 40 are the same number and not the same
  measurement. `research-scope.edn` makes the denominator a required part of
  a versioned observation (\"denominator where applicable\"), and
  `:missing-is-unmeasured` forbids dressing its absence up as neutrality.
  This contract closes that hole. It answers exactly one auditable question:
  **for one derived tally, which denominator attributes did the caller
  declare, with what provenance — and what does that declaration (or its
  honest absence) mean for cross-context comparison?**

    * the denominator is DECLARED, never inferred: the caller supplies
      attributes (key, value, provenance) or the explicit declaration
      {:denominator/declared false}. A SILENT absence — no declaration at
      all — refuses the audit whole rather than reading as \"no denominators
      needed\" (`:missing-denominator-declaration`);
    * a declared attribute carries its own provenance (:source-url,
      :observed-at, :content-hash). An attribute without provenance is
      enumerated as :denominator-malformed and never folded into the
      declared set — an unprovenanced denominator would be exactly the kind
      of untraceable base this contract exists to expose;
    * absence is a finding, not an imputation: a missing attribute is
      flagged and kept missing. No default total, no registry-wide guess,
      no \"assume the field base\" — `:missing-is-unmeasured` is hardwired;
    * NO RATIO IS EVER COMPUTED. Normalizing a tally by a denominator
      produces a rate, and rates across subjects are a ranking in disguise.
      This module records which denominators were declared; deciding
      comparability is a consumer's explicit, auditable act
      (`:normalization-forbidden`);
    * the observation records comparison SUPPORT, never a comparison claim:
      :with-declared-denominators / :no-denominator-declared /
      :no-well-formed-denominator. None of these states asserts the tally
      IS comparable — they record what a future comparison could rest on;
    * coverage is always :partial — the audit says nothing about tallies
      or candidate bases outside the supplied record.

  Self-contained by design: no dependency on any other analytics namespace.
  Pure functions only: no network, no clock, no file I/O. Determinism is a
  test fixture (byte-identical pr-str across runs).")

;; ---------------------------------------------------------------------------
;; Dimension set — mirrors analytics.impact-observation / retraction-observation
;; ---------------------------------------------------------------------------

(def known-dimensions
  #{:scholarly-citation :replication :correction :retraction :policy-citation
    :patent-citation :standard-adoption :clinical-guideline-citation
    :dataset-or-software-reuse})

;; ---------------------------------------------------------------------------
;; Denominator declaration validation
;; ---------------------------------------------------------------------------

(defn- provenanced? [a]
  (and (seq (:source-url a))
       (integer? (:observed-at a))
       (seq (:content-hash a))))

(defn- conformant-attribute? [a]
  (and (map? a)
       (keyword? (:denominator/key a))
       (integer? (:denominator/value a))
       (not (neg? (long (:denominator/value a))))
       (provenanced? a)))

(defn- conformant-declaration? [d]
  (and (map? d)
       (contains? #{true false} (:denominator/declared d))
       (if (:denominator/declared d)
         (vector? (:denominator/attributes d))
         (nil? (:denominator/attributes d)))))

(defn- conformant-tally? [t]
  (and (map? t)
       (contains? known-dimensions (:dimension t))
       (integer? (:tally/count t))
       (not (neg? (long (:tally/count t))))
       (seq (:method-version t))
       (integer? (get-in t [:window :from]))
       (integer? (get-in t [:window :to]))
       (< (long (get-in t [:window :from])) (long (get-in t [:window :to])))))

;; ---------------------------------------------------------------------------
;; Attribute enumeration — declared vs malformed, sorted, duplicates flagged
;; ---------------------------------------------------------------------------

(defn- enumerate-attributes
  "Split declared attributes into well-formed (:declared) and everything
  else (:malformed). A repeated :denominator/key makes every attribute
  carrying that key malformed — the declared base would be ambiguous.
  Both buckets are sorted by key (malformed by serialized shape) so batch
  order never leaks into the record."
  [attrs]
  (let [key-counts (frequencies (keep :denominator/key attrs))
        duplicated? (fn [a] (> (long (get key-counts (:denominator/key a) 0)) 1))
        {dups true, others false} (group-by duplicated? attrs)
        {ok true, bad false} (group-by #(boolean (conformant-attribute? %)) others)
        declared (->> ok
                      (sort-by :denominator/key)
                      (mapv #(select-keys % [:denominator/key :denominator/value
                                             :source-url :observed-at :content-hash])))
        malformed (->> (concat bad dups)
                       (sort-by (fn [a] (pr-str (select-keys a [:denominator/key]))))
                       (mapv #(select-keys % [:denominator/key :denominator/value
                                              :source-url :observed-at :content-hash])))]
    {:declared declared
     :malformed malformed}))

;; ---------------------------------------------------------------------------
;; Derivation
;; ---------------------------------------------------------------------------

(defn derive-denominator
  "Enumerate the declared denominator attributes for one tally. Returns
  {:declared [..] :malformed [..]}. Declared attributes are kept verbatim
  (provenance included); malformed ones are enumerated whole — never
  counted as declared, never imputed."
  [declaration]
  (if (and (map? declaration) (true? (:denominator/declared declaration)))
    (enumerate-attributes (:denominator/attributes declaration))
    {:declared [] :malformed []}))

(defn derive-flags
  "Uncertainty/coverage flags over the denominator audit. Comparison support
  is a record of what was declared — never a claim that the tally is
  comparable, and never a claim that an undeclared denominator was absent
  in the world (only that none was declared to this audit)."
  [derived declaration]
  (let [{:keys [declared malformed]} derived
        declared? (and (map? declaration) (true? (:denominator/declared declaration)))
        support (cond
                  (and declared? (pos? (count declared))) :with-declared-denominators
                  (and declared? (pos? (count malformed))) :no-well-formed-denominator
                  declared? :no-well-formed-denominator
                  :else :no-denominator-declared)]
    {:denominator-declared (boolean declared?)
     :attributes-declared (count declared)
     :attributes-malformed (count malformed)
     :comparison-support support
     :missing-is-unmeasured true
     :normalization-forbidden true
     :ranking-forbidden true
     :causal-claims-forbidden true
     :suggestion-only true
     :coverage :partial}))

;; ---------------------------------------------------------------------------
;; Observation builder
;; ---------------------------------------------------------------------------

(defn build-denominator-observation
  "Build one denominator-observation/v1 record for `tally` (a derived-tally
  map: :dimension, :tally/count, :window {:from, :to}, :method-version)
  against `declaration` — either {:denominator/declared true
  :denominator/attributes [...]} or the honest {:denominator/declared false}.
  `method-version` is the AUDIT's own caller-supplied version, stamped
  verbatim; the tally's :method-version is preserved as the derivation that
  produced the tally. Structural refusals are hardwired: a silent absence of
  declaration refuses the audit whole rather than reading as neutrality."
  [method-version tally declaration]
  (cond
    (not (map? tally))
    [:rejected :tally-not-map]

    (not (seq method-version))
    [:rejected :missing-method-version]

    (not (conformant-tally? tally))
    [:rejected :malformed-tally]

    (or (nil? declaration) (not (map? declaration)))
    [:rejected :missing-denominator-declaration]

    (not (conformant-declaration? declaration))
    [:rejected :malformed-denominator-declaration]

    :else
    (let [derived (derive-denominator declaration)]
      [:accepted
       {:contract "denominator-observation"
        :version "v1"
        :method-version method-version
        :tally-method-version (:method-version tally)
        :dimension (:dimension tally)
        :tally-count (:tally/count tally)
        :window (:window tally)
        :denominator {:declared (:declared derived)
                      :malformed (:malformed derived)
                      :declaration-asserted (:denominator/declared declaration)}
        :flags (derive-flags derived declaration)
        :ranking nil
        :ranking-forbidden true
        :causal-claims-forbidden true
        :normalization-forbidden true
        :claims []}])))

;; ---------------------------------------------------------------------------
;; Append-only refresh history
;; ---------------------------------------------------------------------------

(defn refresh
  "Append a new denominator audit onto the history of audit records. Prior
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
  "Deterministic identity for one denominator audit. Maps are sorted before
  serialization so iteration order never leaks into the key. Pure string —
  no crypto, no clock."
  [audit]
  (when (map? audit)
    (str "denominator-observation/v1:"
         (pr-str {:method-version (:method-version audit)
                  :tally-method-version (:tally-method-version audit)
                  :dimension (:dimension audit)
                  :tally-count (:tally-count audit)
                  :window (into (sorted-map) (:window audit))
                  :denominator (into (sorted-map)
                                     (map (fn [a] [(:denominator/key a)
                                                   (:denominator/value a)])
                                          (:declared (:denominator audit))))
                  :attributes-malformed (count (:malformed (:denominator audit)))
                  :flags (select-keys (:flags audit)
                                      [:denominator-declared
                                       :attributes-declared
                                       :attributes-malformed
                                       :comparison-support])}))))

(defn hyakka-proposal
  "Proposal payload for the Hyakka wiki, or nil for an audit with no
  observable content — a record that would carry neither a declared
  denominator nor an honest declaration dresses absence up as data."
  [audit]
  (when (and (map? audit)
             (or (pos? (long (or (:attributes-declared (:flags audit)) 0)))
                 (pos? (long (or (:attributes-malformed (:flags audit)) 0)))
                 (and (map? (:denominator audit))
                      (false? (:declaration-asserted (:denominator audit))))))
    {:proposal/type :denominator-observation
     :proposal/dedupe-key (dedupe-key audit)
     :proposal/contract "denominator-observation/v1"
     :proposal/method-version (:method-version audit)
     :proposal/tally-method-version (:tally-method-version audit)
     :proposal/dimension (:dimension audit)
     :proposal/tally-count (:tally-count audit)
     :proposal/window (:window audit)
     :proposal/denominator (:denominator audit)
     :proposal/flags (:flags audit)
     :proposal/coverage :partial
     :proposal/suggestion-only true
     :proposal/normalization-forbidden true
     :proposal/ranking nil
     :proposal/ranking-forbidden true
     :proposal/causal-claims-forbidden true
     :proposal/claims []
     :proposal/note "denominator-declaration-audit-recorded-not-computed-comparability-is-the-consumers-act"}))

(defn hyakka-readback-accept?
  "Accept a readback only if it is still the record this actor proposed: same
  contract, same dedupe-key, same method-versions, same tally shape, same
  denominator enumeration and flags — and the structural refusals (no
  ranking, no causal claims, no normalization, suggestion-only) not
  stripped. Anything else is tampering; refuse."
  [proposal readback]
  (and (map? readback)
       (= "denominator-observation/v1" (:proposal/contract readback))
       (some? (:proposal/dedupe-key readback))
       (= (:proposal/dedupe-key proposal) (:proposal/dedupe-key readback))
       (= (:proposal/method-version proposal) (:proposal/method-version readback))
       (= (:proposal/tally-method-version proposal) (:proposal/tally-method-version readback))
       (= (:proposal/dimension proposal) (:proposal/dimension readback))
       (= (:proposal/tally-count proposal) (:proposal/tally-count readback))
       (= (:proposal/window proposal) (:proposal/window readback))
       (= (:proposal/denominator proposal) (:proposal/denominator readback))
       (= (:proposal/flags proposal) (:proposal/flags readback))
       (some? (:proposal/flags readback))
       (= :partial (:proposal/coverage readback))
       (true? (:proposal/suggestion-only readback))
       (true? (:proposal/normalization-forbidden readback))
       (true? (:proposal/ranking-forbidden readback))
       (true? (:proposal/causal-claims-forbidden readback))
       (nil? (:proposal/ranking readback))
       (empty? (:proposal/claims readback))))
