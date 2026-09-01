(ns analytics.provenance-diversity
  "provenance-diversity-observation/v1 — bounded, provenance-preserving audit of
  WHERE a window's admitted signals came from, for the cloud-itonami analytics
  actor.

  This contract answers one auditable question only: **for a fixed window,
  which admitted source classes and dimension×source-class pairs are present,
  and which dimensions rest on a single source class?** It is a composition
  audit, not a score:

    * source classes are tallied ADDITIVELY and never combined into a single
      diversity/quality number — a \"diversity score\" would quietly assert
      that a citation registry and a retraction notice measure the same thing,
      which they do not (citation is not support; attention is not impact);
    * corroboration is reported as a PRESENCE per dimension (observed by ≥2
      distinct allowed source classes with distinct content hashes), never as
      agreement strength — two sources repeating one claim is a fact about
      sources, not evidence that the claim is true;
    * the same content hash observed through two different source classes is
      FLAGGED (:same-artifact-via-multiple-sources), never merged or deduped
      into one signal;
    * missing provenance, forbidden source classes and out-of-window signals
      are refused/excluded through the same admission rules as
      influence-observation/v1 (analytics.impact-observation), so the two
      contracts can never disagree about what was admitted;
    * coverage is always :partial — the audit says nothing about data outside
      the supplied batch.

  Pure functions only: no network, no clock, no file I/O. Determinism is a
  test fixture (byte-identical pr-str across runs).")

;; ---------------------------------------------------------------------------
;; Dimension set — mirrors analytics.impact-observation / research-scope.edn
;; ---------------------------------------------------------------------------

(def known-dimensions
  #{:scholarly-citation :replication :correction :retraction :policy-citation
    :patent-citation :standard-adoption :clinical-guideline-citation
    :dataset-or-software-reuse})

;; ---------------------------------------------------------------------------
;; Derived composition — additive per-source-class structure only
;; ---------------------------------------------------------------------------

(defn derive-source-composition
  "Per-source-class counts and the dimension×source-class matrix over admitted
  signals. Every map is sorted before serialization so iteration order never
  leaks into output or the dedupe key. A signal is an observation event, not a
  weight: one admitted signal contributes exactly 1."
  [admitted]
  (let [by-class (group-by :source/source-class admitted)
        matrix   (reduce
                  (fn [m s]
                    (let [k [(:dimension s) (:source/source-class s)]]
                      (update m k (fnil inc 0))))
                  {}
                  admitted)]
    {:source-class-counts
     (into (sorted-map) (map (fn [[c ss]] [c (count ss)]) by-class))
     :dimension-source-matrix
     (into (sorted-map)
           (map (fn [[[d c] n]] [[d c] n]))
           matrix)}))

(defn- corroborated? [pair-counts dimension]
  (let [classes (keep (fn [entry]
                        (let [[d c] (first entry)]
                          (when (and (= d dimension) (pos? (second entry))) c)))
                      pair-counts)]
    (>= (count classes) 2)))

(defn derive-corroboration
  "Per-dimension corroboration PRESENCE. A dimension is
  :multi-source-corroborated when admitted signals for it come from ≥2 distinct
  allowed source classes AND those signals do not all repeat one content hash
  (same hash everywhere is one artifact echoing, not independent observation).
  Presence is descriptive only — it is never a weight, never a score, and
  never read as agreement about truth."
  [admitted]
  (let [pair-counts (:dimension-source-matrix (derive-source-composition admitted))
        by-dim      (group-by :dimension admitted)]
    (loop [dims (sort (set (map first (keys pair-counts))))
           acc {}]
      (if (seq dims)
        (let [d (first dims)
              ss (get by-dim d)]
          (recur (rest dims)
                 (assoc acc d
                        (if (and (corroborated? pair-counts d)
                                 (>= (count (distinct (map :content-hash ss))) 2))
                          :multi-source-corroborated
                          :not-corroborated))))
        acc))))

(defn derive-flags
  "Uncertainty / coverage flags over the composition. Absence produces the
  flag, never a zero."
  [admitted excluded-out-of-window rejected]
  (let [composition  (derive-source-composition admitted)
        hashes-by-class (group-by :source/source-class
                                  (remove nil? (map :content-hash admitted)))
        cross-class-dupes
        (boolean
         (some (fn [h]
                 (>= (count (distinct
                             (map :source/source-class
                                  (filter #(= h (:content-hash %)) admitted))))
                     2))
               (distinct (remove nil? (map :content-hash admitted)))))
        dims-present (sort (into [] (distinct (map :dimension admitted))))
        corr         (derive-corroboration admitted)
        uncorroborated (sort (into [] (keep (fn [[d v]] (when (= :not-corroborated v) d)) corr)))]
    {:missing-is-unmeasured true
     :excluded-signals-present (pos? (count excluded-out-of-window))
     :rejected-reasons (frequencies rejected)
     :single-source-dependency
     (boolean (and (seq admitted)
                   (= 1 (count (:source-class-counts composition)))))
     :same-artifact-via-multiple-sources cross-class-dupes
     :dimensions-never-corroborated uncorroborated
     :dimensions-observed dims-present
     :source-class-count (count (:source-class-counts composition))
     :coverage :partial
     :excluded-out-of-window
     (mapv (fn [s] {:observed-at (:observed-at s)
                    :source-class (:source/source-class s)})
           excluded-out-of-window)}))

(defn- observation-conformant-signal? [s]
  ;; re-validate the minimum provenance shape locally so this contract does
  ;; not silently accept batches that bypass analytics.impact-observation's
  ;; admission rules (same allow/forbid sets, same required fields).
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

(defn build-observation
  "Build one provenance-diversity-observation/v1 record.

  `method-version` is caller-supplied and stamped verbatim. The batch arrives
  pre-partitioned from analytics.impact-observation/partition-signals: signals
  with missing provenance, an unknown dimension or a source class outside the
  impact-allow set are refused there and enumerated verbatim in
  :flags/:rejected-reasons — they are never averaged in. The admitted bucket
  is re-validated here as a guard so a batch that bypassed admission cannot
  reach the derivation silently. No aggregate number is derived across source
  classes — the structural refusals are hardwired."
  [method-version partitioned window subject]
  (let [{:keys [admitted excluded-out-of-window rejected]} partitioned
        {:keys [from to]} window]
    (cond
      (not (vector? admitted))
      [:rejected :admitted-not-vector]
      (or (not (integer? from)) (not (integer? to)) (<= to from))
      [:rejected :malformed-window]
      (some (complement observation-conformant-signal?) admitted)
      [:rejected :non-conformant-signal-in-batch]
      :else
      [:accepted
       {:contract "provenance-diversity-observation"
        :version "v1"
        :method-version method-version
        :subject subject
        :window {:from from :to to}
        :admitted-count (count admitted)
        :composition (derive-source-composition admitted)
        :corroboration (derive-corroboration admitted)
        :flags (derive-flags admitted excluded-out-of-window rejected)
        :ranking nil
        :ranking-forbidden true
        :causal-claims-forbidden true
        :diversity-score nil
        :diversity-score-forbidden true
        :claims []}])))

;; ---------------------------------------------------------------------------
;; Append-only refresh history
;; ---------------------------------------------------------------------------

(defn refresh
  "Append a new diversity observation onto the history of records. Prior
  records are immutable; nothing is rewritten."
  [history obs]
  (conj (vec history) obs))

(defn history-records
  "The append-only list of prior diversity records."
  [history]
  (vec history))

;; ---------------------------------------------------------------------------
;; Hyakka proposal / readback
;; ---------------------------------------------------------------------------

(defn dedupe-key
  "Deterministic identity for one composition audit. Sorted maps and vectors
  so iteration order never leaks into the key. Pure string — no crypto, no
  clock."
  [obs]
  (when (map? obs)
    (str "provenance-diversity-observation/v1:"
         (pr-str {:subject (:subject obs)
                  :window (:window obs)
                  :method-version (:method-version obs)
                  :admitted-count (:admitted-count obs)
                  :composition (:composition obs)
                  :corroboration (:corroboration obs)}))))

(defn hyakka-proposal
  "Proposal payload for the Hyakka wiki, or nil when nothing was admitted —
  an empty batch measures nothing and proposing a record for it would dress
  absence up as data."
  [obs]
  (when (and (map? obs) (pos? (long (or (:admitted-count obs) 0))))
    {:proposal/type :provenance-diversity-observation
     :proposal/dedupe-key (dedupe-key obs)
     :proposal/contract "provenance-diversity-observation/v1"
     :proposal/method-version (:method-version obs)
     :proposal/subject (:subject obs)
     :proposal/window (:window obs)
     :proposal/composition (:composition obs)
     :proposal/corroboration (:corroboration obs)
     :proposal/flags (:flags obs)
     :proposal/coverage :partial
     :proposal/ranking nil
     :proposal/ranking-forbidden true
     :proposal/causal-claims-forbidden true
     :proposal/diversity-score nil
     :proposal/diversity-score-forbidden true
     :proposal/claims []
     :proposal/note "composition-audit-not-quality-score-corroboration-is-not-agreement"}))

(defn hyakka-readback-accept?
  "Accept a readback only if it is still the record this actor proposed: same
  contract, same dedupe-key, same method-version, same composition and
  corroboration, same flags — and the structural refusals (no ranking, no
  causal claims, no aggregate diversity score) not stripped. Anything else is
  tampering; refuse."
  [proposal readback]
  (and (map? readback)
       (= "provenance-diversity-observation/v1" (:proposal/contract readback))
       (some? (:proposal/dedupe-key readback))
       (= (:proposal/dedupe-key proposal) (:proposal/dedupe-key readback))
       (= (:proposal/method-version proposal) (:proposal/method-version readback))
       (= (:proposal/subject proposal) (:proposal/subject readback))
       (= (:proposal/window proposal) (:proposal/window readback))
       (= (:proposal/composition proposal) (:proposal/composition readback))
       (= (:proposal/corroboration proposal) (:proposal/corroboration readback))
       (= (:proposal/flags proposal) (:proposal/flags readback))
       (some? (:proposal/flags readback))
       (contains? (:proposal/flags readback) :missing-is-unmeasured)
       (= :partial (:proposal/coverage readback))
       (true? (:proposal/ranking-forbidden readback))
       (true? (:proposal/causal-claims-forbidden readback))
       (nil? (:proposal/ranking readback))
       (nil? (:proposal/diversity-score readback))
       (true? (:proposal/diversity-score-forbidden readback))
       (empty? (:proposal/claims readback))))
