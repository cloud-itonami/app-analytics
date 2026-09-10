(ns analytics.denominator-observation-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [analytics.denominator-observation :as dno]))

;; ---------------------------------------------------------------------------
;; Fixture builders
;; ---------------------------------------------------------------------------

(defn- attr
  ([] (attr {}))
  ([{:keys [k v hash url observed-at]
     :or {k :candidate-works-exposed
          v 10000
          hash "h-den-1"
          url "https://registry.example/coverage"
          observed-at 1500}}]
   {:denominator/key k
    :denominator/value v
    :source-url url
    :observed-at observed-at
    :content-hash hash}))

(defn- tally
  ([] (tally {}))
  ([{:keys [dimension count from to mv]
     :or {dimension :scholarly-citation
          count 50
          from 1000
          to 2000
          mv "influence-observation/v1"}}]
   {:dimension dimension
    :tally/count count
    :window {:from from :to to}
    :method-version mv}))

(defn- declared-decl [attrs] {:denominator/declared true :denominator/attributes attrs})

(defn- audit-of [t d]
  (second (dno/build-denominator-observation "mv-audit-1" t d)))

(defn- flags [audit] (:flags audit))

(deftest build-refusals
  ;; non-map tally
  (is (= [:rejected :tally-not-map]
         (dno/build-denominator-observation "mv" [1 2] (declared-decl [(attr)]))))
  ;; missing audit method-version
  (is (= [:rejected :missing-method-version]
         (dno/build-denominator-observation "" (tally) (declared-decl [(attr)]))))
  (is (= [:rejected :missing-method-version]
         (dno/build-denominator-observation nil (tally) (declared-decl [(attr)]))))
  ;; malformed tally — unknown dimension, bad window, negative count
  (is (= [:rejected :malformed-tally]
         (dno/build-denominator-observation "mv" (tally {:dimension :citations})
                                            (declared-decl [(attr)]))))
  (is (= [:rejected :malformed-tally]
         (dno/build-denominator-observation "mv" (tally {:from 2000 :to 1000})
                                            (declared-decl [(attr)]))))
  (is (= [:rejected :malformed-tally]
         (dno/build-denominator-observation "mv" (tally {:count -1})
                                            (declared-decl [(attr)]))))
  (is (= [:rejected :malformed-tally]
         (dno/build-denominator-observation "mv" (tally {:from "1000"})
                                            (declared-decl [(attr)]))))
  ;; SILENT absence of a declaration refuses the whole — it must never read
  ;; as "no denominator needed"
  (is (= [:rejected :missing-denominator-declaration]
         (dno/build-denominator-observation "mv" (tally) nil)))
  (is (= [:rejected :missing-denominator-declaration]
         (dno/build-denominator-observation "mv" (tally) "declared")))
  ;; malformed declaration shape
  (is (= [:rejected :malformed-denominator-declaration]
         (dno/build-denominator-observation "mv" (tally) {:denominator/declared true})))
  (is (= [:rejected :malformed-denominator-declaration]
         (dno/build-denominator-observation "mv" (tally)
                                            {:denominator/declared "yes"
                                             :denominator/attributes []})))
  (is (= [:rejected :malformed-denominator-declaration]
         (dno/build-denominator-observation "mv" (tally)
                                            {:denominator/declared false
                                             :denominator/attributes "none"}))))

(deftest declared-denominators-enumerated-with-provenance
  ;; declared attributes are kept verbatim — value AND provenance
  (let [a1 (attr {:k :candidate-works-exposed :v 10000 :hash "h1"})
        a2 (attr {:k :registry-coverage-year :v 2025 :hash "h2"})
        audit (audit-of (tally) (declared-decl [a1 a2]))]
    (is (= :accepted (first (dno/build-denominator-observation "mv-audit-1" (tally)
                                                              (declared-decl [a1 a2])))))
    (is (= 2 (count (:declared (:denominator audit)))))
    (is (= 0 (count (:malformed (:denominator audit)))))
    (is (= {:denominator/key :candidate-works-exposed
            :denominator/value 10000
            :source-url "https://registry.example/coverage"
            :observed-at 1500
            :content-hash "h1"}
           (first (:declared (:denominator audit)))))
    (is (= :with-declared-denominators (:comparison-support (flags audit))))
    (is (true? (:denominator-declared (flags audit))))
    ;; the tally's own method-version is preserved as the derivation
    (is (= "influence-observation/v1" (:tally-method-version audit)))))

(deftest malformed-attributes-enumerated-not-counted
  ;; an attribute without provenance is enumerated as malformed — never
  ;; folded into the declared base, never imputed
  (let [audit (audit-of (tally)
                        (declared-decl [(attr)
                                        {:denominator/key :unprovenanced-total
                                         :denominator/value 9999}
                                        (attr {:k :coverage-year :v "2025"})]))]
    (is (= 1 (count (:declared (:denominator audit)))))
    (is (= 2 (count (:malformed (:denominator audit)))))
    (is (= :with-declared-denominators (:comparison-support (flags audit))))
    (is (= 2 (:attributes-malformed (flags audit))))))

(deftest duplicate-keys-make-base-ambiguous
  ;; two attributes claiming the same key would make the declared base
  ;; ambiguous — every attribute carrying that key is malformed
  (let [audit (audit-of (tally)
                        (declared-decl [(attr {:k :candidate-works-exposed :hash "h1"})
                                        (attr {:k :candidate-works-exposed :hash "h2" :v 20000})]))]
    (is (= 0 (count (:declared (:denominator audit)))))
    (is (= 2 (count (:malformed (:denominator audit)))))
    (is (= :no-well-formed-denominator (:comparison-support (flags audit))))))

(deftest honest-undeclared-is-recorded-not-imputed
  ;; {:denominator/declared false} is an HONEST declaration of absence —
  ;; recorded as :no-denominator-declared. It is NOT "the tally needs no
  ;; denominator" and NOT "no denominator exists in the world";
  ;; missing-is-unmeasured stays hardwired.
  (let [audit (audit-of (tally) {:denominator/declared false})]
    (is (= 0 (count (:declared (:denominator audit)))))
    (is (false? (:denominator-declared (flags audit))))
    (is (= :no-denominator-declared (:comparison-support (flags audit))))
    (is (true? (:missing-is-unmeasured (flags audit))))
    ;; the proposal is still publishable — the honest declaration IS content
    (is (some? (dno/hyakka-proposal audit)))))

(deftest declaration-with-no-well-formed-attributes
  (let [audit (audit-of (tally) (declared-decl [{:denominator/key :k}]))]
    (is (= :no-well-formed-denominator (:comparison-support (flags audit))))
    (is (= 1 (:attributes-malformed (flags audit))))))

(deftest no-ratio-is-ever-computed
  ;; the observation exposes declared values, never a normalized rate —
  ;; a rate across subjects would be a ranking in disguise
  (let [audit (audit-of (tally {:count 50}) (declared-decl [(attr {:v 10000})]))]
    (is (nil? (get-in audit [:denominator :ratio])))
    (is (true? (:normalization-forbidden (flags audit))))
    (is (nil? (:ranking audit)))
    (is (true? (:ranking-forbidden (flags audit))))
    (is (empty? (:claims audit)))))

(deftest determinism-byte-identical
  ;; two runs over the same input produce byte-identical records, in any
  ;; batch order
  (let [a1 (attr {:k :candidate-works-exposed :hash "h1"})
        a2 (attr {:k :registry-coverage-year :v 2025 :hash "h2"})
        run1 (audit-of (tally) (declared-decl [a1 a2]))
        run2 (audit-of (tally) (declared-decl [a2 a1]))]
    (is (= (pr-str run1) (pr-str run2)))
    (is (= (dno/dedupe-key run1) (dno/dedupe-key run2)))))

(deftest refresh-history-append-only
  (let [audit1 (audit-of (tally) (declared-decl [(attr)]))
        audit2 (audit-of (tally {:count 80 :mv "influence-observation/v2"})
                         {:denominator/declared false})
        history (-> [] (dno/refresh audit1) (dno/refresh audit2))]
    (is (= audit1 (first history)))
    (is (= audit2 (last history)))
    (is (= 2 (count history)))
    (is (= history (dno/history-records history)))))

(deftest hyakka-proposal-nil-when-no-content
  ;; a record with neither declared attributes, malformed attributes, nor an
  ;; honest declaration carries nothing observable — proposing it would
  ;; dress absence up as data. (This state is unreachable through the
  ;; builder — declared true with empty attributes lands on
  ;; :no-well-formed-denominator which has malformed content — so nil is
  ;; only for malformed/absent audits.)
  (is (nil? (dno/hyakka-proposal nil)))
  (is (nil? (dno/hyakka-proposal "not-a-record"))))

(deftest hyakka-proposal-and-readback
  (let [audit (audit-of (tally {:count 50})
                        (declared-decl [(attr {:k :candidate-works-exposed :v 10000 :hash "h1"})
                                        (attr {:k :registry-coverage-year :v 2025 :hash "h2"})]))
        proposal (dno/hyakka-proposal audit)]
    ;; proposal carries the structural refusals
    (is (some? proposal))
    (is (= "denominator-observation/v1" (:proposal/contract proposal)))
    (is (true? (:proposal/normalization-forbidden proposal)))
    (is (true? (:proposal/ranking-forbidden proposal)))
    (is (true? (:proposal/causal-claims-forbidden proposal)))
    (is (nil? (:proposal/ranking proposal)))
    (is (empty? (:proposal/claims proposal)))
    (is (= :partial (:proposal/coverage proposal)))
    ;; readback of the identical payload accepted
    (is (true? (dno/hyakka-readback-accept? proposal proposal)))
    ;; tampered readback refused
    (is (false? (dno/hyakka-readback-accept?
                 proposal (assoc proposal :proposal/normalization-forbidden false))))
    (is (false? (dno/hyakka-readback-accept?
                 proposal (assoc proposal :proposal/ranking [{:r 1}]))))
    (is (false? (dno/hyakka-readback-accept?
                 proposal (assoc proposal :proposal/claims ["causal claim"]))))
    (is (false? (dno/hyakka-readback-accept?
                 proposal (dissoc proposal :proposal/dedupe-key))))
    (is (false? (dno/hyakka-readback-accept?
                 proposal (assoc proposal :proposal/coverage :complete))))
    (is (false? (dno/hyakka-readback-accept?
                 proposal (update-in proposal [:proposal/denominator :declared]
                                     (fn [d] (mapv #(assoc % :denominator/value 999) d))))))
    (is (false? (dno/hyakka-readback-accept?
                 proposal (assoc proposal :proposal/method-version "mv-2"))))
    (is (false? (dno/hyakka-readback-accept?
                 proposal (update proposal :proposal/tally-count inc))))
    (is (false? (dno/hyakka-readback-accept?
                 proposal (assoc proposal :proposal/tally-method-version "other/v1"))))))
