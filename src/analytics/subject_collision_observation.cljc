(ns analytics.subject-collision-observation
  "subject-collision-observation/v1 — bounded subject-identity variant audit
  over a history of influence-observation/v1 records.

  This contract answers one auditable question only: **which records carry
  subject strings that differ only superficially (whitespace edges / letter
  case) from another record's subject, and are therefore collision candidates
  for any downstream grouping?** It is a collision audit, not an identity
  resolution:

    * candidate subjects are FLAGGED and preserved VERBATIM — never merged,
      never rewritten, never canonicalized. Deciding that \" researchers-a \"
      and \"researchers-a\" are the same person is a judgment this contract
      must not make (identity resolution is forbidden: `:identity-resolution-forbidden`);
    * the canonical form is computed only as a COMPARISON KEY (whitespace
      trimmed, lowercased, applied symmetrically to every record) and is
      stamped into the derived record so the caller can audit exactly what
      collided. The comparison key never replaces the verbatim subject in
      any output surface;
    * only two normalizations are applied — `.trim` on whitespace edges and
      lowercase on ASCII letters — so the key is deterministic across
      runtimes. Any other similarity notion (fuzzy match, edit distance,
      nickname, transliteration) is out of scope and structurally absent;
    * collision groups travel with the records that produced them, verbatim
      and additive. Nothing is deduplicated, netted, or reconciled;
    * records whose subject is already unique under the comparison key are
      reported as `:distinct-subjects`, not dropped;
    * coverage is always :partial — absence of collisions inside the supplied
      history says nothing about collisions outside it;
    * no ranking, no causal claim, no people-level inference of any kind
      (reputation, employability, funding worthiness are never touched).

  Pure functions only: no network, no clock, no file I/O. Determinism is a
  test fixture (byte-identical pr-str across runs). Self-contained by
  design: no dependency on any other analytics namespace beyond the
  influence-observation/v1 record shape it audits.")

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
;; Subject comparison key — the ONLY normalization, applied symmetrically
;; ---------------------------------------------------------------------------

(defn- subject-string
  "The verbatim subject as a string. Non-string subjects (maps, numbers) are
  carried through pr-str so they still group deterministically; they are
  never coerced into looking like a name."
  [o]
  (let [s (:subject o)]
    (cond (string? s) s
          (nil? s) nil
          :else (pr-str s))))

(defn- comparison-key
  "Whitespace-edge trim + ASCII lowercase. Exactly two normalizations — no
  more, no fewer. Symmetric: applied identically to every record, so the
  key cannot smuggle a direction into the comparison."
  [s]
  (when (some? s)
    (clojure.string/lower-case (clojure.string/trim s))))

;; ---------------------------------------------------------------------------
;; Per-key collision groups — verbatim subjects, additive counts
;; ---------------------------------------------------------------------------

(defn- analyze-groups
  "Group the history by comparison key. Returns a sorted map:
  key -> {:subjects (sorted distinct verbatim subjects)
          :variants  (count of distinct verbatim subjects)
          :record-count (records carrying this key)
          :collision-candidate? (more than one distinct verbatim subject)}."
  [history]
  (->> history
       (group-by #(comparison-key (subject-string %)))
       (into (sorted-map)
             (map (fn [[k rs]]
                    [k {:subjects (vec (sort (distinct (keep subject-string rs))))
                        :variants (count (distinct (keep subject-string rs)))
                        :record-count (count rs)
                        :collision-candidate? (> (count (distinct (keep subject-string rs))) 1)}])))))

(defn derive-groups [history] (analyze-groups history))

(defn derive-flags
  "Uncertainty/coverage flags over the collision audit. The refusals are
  HARDWIRED — they hold even when no collision is present, because the
  boundary is a property of this contract, not of the data."
  [history]
  (let [groups (derive-groups history)
        collisions (filter :collision-candidate? (vals groups))]
    {:identities (count groups)
     :collision-groups (count collisions)
     :collision-records (reduce + 0 (map :record-count collisions))
     :identity-resolution-forbidden true
     :merge-forbidden true
     :empty-history (empty? history)
     :coverage :partial}))

(defn build-subject-collision-observation
  "Build one subject-collision-observation/v1 record over `history` (a vector
  of influence-observation/v1 maps). A non-vector history or a non-conformant
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
       {:contract "subject-collision-observation"
        :version "v1"
        :history-size (count history)
        :coverage-window (when (seq history)
                           {:from (apply min (map #(get-in % [:window :from]) history))
                            :to (apply max (map #(get-in % [:window :to]) history))})
        :groups (derive-groups history)
        :flags (derive-flags history)
        :resolution nil
        :identity-resolution-forbidden true
        :merge-forbidden true
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
  "Append a new subject-collision observation onto the history of audit
  records. Prior records are immutable; nothing is rewritten."
  [history audit]
  (conj (vec history) audit))

(defn history-records
  "The append-only list of prior subject-collision records."
  [history]
  (vec history))

;; ---------------------------------------------------------------------------
;; Hyakka proposal / readback
;; ---------------------------------------------------------------------------

(defn dedupe-key
  "Deterministic identity for one collision audit. Sorted maps and vectors so
  iteration order never leaks into the key. Pure string — no crypto, no
  clock."
  [audit]
  (when (map? audit)
    (str "subject-collision-observation/v1:"
         (pr-str {:history-size (:history-size audit)
                  :coverage-window (:coverage-window audit)
                  :groups (:groups audit)
                  :flags (select-keys (:flags audit)
                                      [:identities
                                       :collision-groups
                                       :collision-records
                                       :identity-resolution-forbidden
                                       :merge-forbidden])}))))

(defn hyakka-proposal
  "Proposal payload for the Hyakka wiki, or nil when the history is empty —
  an empty history measures nothing and proposing a record for it would dress
  absence up as data."
  [audit]
  (when (and (map? audit)
             (pos? (long (or (:history-size audit) 0))))
    {:proposal/type :subject-collision
     :proposal/dedupe-key (dedupe-key audit)
     :proposal/contract "subject-collision-observation/v1"
     :proposal/history-size (:history-size audit)
     :proposal/coverage-window (:coverage-window audit)
     :proposal/groups (:groups audit)
     :proposal/flags (:flags audit)
     :proposal/coverage :partial
     :proposal/resolution nil
     :proposal/identity-resolution-forbidden true
     :proposal/merge-forbidden true
     :proposal/aggregate nil
     :proposal/aggregation-forbidden true
     :proposal/ranking nil
     :proposal/ranking-forbidden true
     :proposal/causal-claims-forbidden true
     :proposal/claims []
     :proposal/note "collision-candidates-flagged-never-merged-identity-resolution-forbidden"}))

(defn hyakka-readback-accept?
  "Accept a readback only if it is still the record this actor proposed: same
  contract, same dedupe-key, same groups and flags, same history size — and
  the structural refusals not stripped. Anything else is tampering; refuse."
  [proposal readback]
  (and (map? readback)
       (= "subject-collision-observation/v1" (:proposal/contract readback))
       (some? (:proposal/dedupe-key readback))
       (= (:proposal/dedupe-key proposal) (:proposal/dedupe-key readback))
       (= (:proposal/history-size proposal) (:proposal/history-size readback))
       (= (:proposal/coverage-window proposal) (:proposal/coverage-window readback))
       (= (:proposal/groups proposal) (:proposal/groups readback))
       (= (:proposal/flags proposal) (:proposal/flags readback))
       (some? (:proposal/flags readback))
       (contains? (:proposal/flags readback) :collision-records)
       (true? (get-in readback [:proposal/flags :identity-resolution-forbidden]))
       (true? (get-in readback [:proposal/flags :merge-forbidden]))
       (nil? (:proposal/resolution readback))
       (true? (:proposal/identity-resolution-forbidden readback))
       (true? (:proposal/merge-forbidden readback))
       (= :partial (:proposal/coverage readback))
       (nil? (:proposal/aggregate readback))
       (true? (:proposal/aggregation-forbidden readback))
       (true? (:proposal/ranking-forbidden readback))
       (true? (:proposal/causal-claims-forbidden readback))
       (nil? (:proposal/ranking readback))
       (empty? (:proposal/claims readback))))
