(ns analytics.evidence-lineage-observation-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [analytics.evidence-lineage-observation :as elo]))

;; ---------------------------------------------------------------------------
;; Fixture builders
;; ---------------------------------------------------------------------------

(defn- sig
  ([] (sig {}))
  ([{:keys [dim src url hash at payload]
     :or {dim :scholarly-citation
          src :citation-registry
          url "https://example.org/cite-1"
          hash "hash-1"
          at 1500
          payload {:title "Some paper"}}}]
   (cond-> {:source/source-class src
            :dimension dim
            :source-url url
            :observed-at at
            :content-hash hash}
     payload (assoc :payload payload))))

(def ^:private default-flags
  {:missing-is-unmeasured true
   :retraction-present false
   :correction-present false
   :single-source-dependency true
   :duplicate-content-hash false
   :coverage :partial})

(defn- stored
  ([] (stored {}))
  ([{:keys [subject from to mv tallies count flags]
     :or {subject {:name "researcher-a"}
          from 1000 to 2000
          mv "mv-1"
          tallies {:tally/scholarly-citation 2}
          count 2
          flags default-flags}}]
   {:contract "influence-observation"
    :version "v1"
    :method-version mv
    :subject subject
    :window {:from from :to to}
    :tallies tallies
    :admitted-count count
    :flags flags
    :ranking nil
    :ranking-forbidden true
    :causal-claims-forbidden true
    :claims []}))

(def ^:private window {:from 1000 :to 2000})
(def ^:private subject {:name "researcher-a"})

(defn- accept
  ([signals so] (elo/build-lineage-observation "lineage-mv-1" window subject signals so))
  ([mv signals so] (elo/build-lineage-observation mv window subject signals so)))

;; ---------------------------------------------------------------------------
;; Refusals
;; ---------------------------------------------------------------------------

(deftest build-refusals
  ;; non-vector signals
  (is (= [:rejected :signals-not-vector]
         (elo/build-lineage-observation
          "lineage-mv-1" window subject (list (sig)) (stored))))
  ;; empty signals measure nothing — refused, not reported as a clean lineage
  (is (= [:rejected :empty-signals]
         (elo/build-lineage-observation "lineage-mv-1" window subject [] (stored))))
  ;; missing audit method-version
  (is (= [:rejected :missing-method-version]
         (accept "" [(sig)] (stored))))
  (is (= [:rejected :missing-method-version]
         (accept nil [(sig)] (stored))))
  ;; malformed replay window
  (is (= [:rejected :malformed-replay-window]
         (elo/build-lineage-observation
          "lineage-mv-1" {:from 1000} subject [(sig)] (stored))))
  (is (= [:rejected :malformed-replay-window]
         (elo/build-lineage-observation
          "lineage-mv-1" {:from 2000 :to 1000} subject [(sig)] (stored))))
  ;; malformed replay subject
  (is (= [:rejected :malformed-replay-subject]
         (elo/build-lineage-observation
          "lineage-mv-1" window "researcher-a" [(sig)] (stored))))
  ;; non-conformant stored observation refuses the whole audit
  (is (= [:rejected :non-conformant-stored-observation]
         (elo/build-lineage-observation
          "lineage-mv-1" window subject [(sig)] {:contract "x"})))
  ;; a malformed signal inside the batch refuses the whole audit
  (is (= [:rejected :malformed-signal-in-batch]
         (accept [(sig) :not-a-map] (stored))))
  ;; identity mismatch: declared replay window differs from the stored
  ;; observation's window — refused, not merged
  (is (= [:rejected :identity-mismatch]
         (elo/build-lineage-observation
          "lineage-mv-1" {:from 1000 :to 2001} subject [(sig)]
          (stored {:to 2000}))))
  (is (= [:rejected :identity-mismatch]
         (elo/build-lineage-observation
          "lineage-mv-1" window {:name "researcher-b"} [(sig)]
          (stored {:subject {:name "researcher-a"}})))))

;; ---------------------------------------------------------------------------
;; Reproducible lineage — full match
;; ---------------------------------------------------------------------------

(deftest replay-match-additive
  (let [signals [(sig {:hash "h1" :at 1100})
                 (sig {:hash "h2" :at 1200})]
        [verdict rec] (accept signals (stored))]
    (is (= :accepted verdict))
    (let [replay (:replay rec)
          lin (:lineage rec)]
      ;; two scholarly-citation signals replay to two tallies
      (is (= 2 (:admitted-count replay)))
      (is (true? (-> (filter #(= :tallies (:surface %)) (:details lin))
                     first :match)))
      (is (true? (:all-match lin)))
      (is (= [] (:mismatched-surfaces lin)))
      (is (= [:tallies :admitted-count :derived-flags]
             (:surfaces-compared lin)))
      (is (true? (get-in rec [:flags :lineage-match]))))))

;; ---------------------------------------------------------------------------
;; Mismatch is a finding, enumerated — never reconciled
;; ---------------------------------------------------------------------------

(deftest replay-mismatch-enumerated
  ;; stored tallies claim 3 but only 2 signals exist
  (let [[verdict rec] (accept [(sig) (sig {:hash "h2"})]
                              (stored {:tallies {:tally/scholarly-citation 3}
                                       :count 3}))
        lin (:lineage rec)
        tally-detail (->> (:details lin)
                          (filter #(= :tallies (:surface %)))
                          first)]
    (is (= :accepted verdict))
    (is (false? (:all-match lin)))
    (is (= [:tallies :admitted-count] (:mismatched-surfaces lin)))
    ;; stored observation travels VERBATIM — never corrected in place
    (is (= 3 (get-in rec [:stored-observation :tallies :tally/scholarly-citation])))
    ;; the finding carries both sides
    (is (= {:tally/scholarly-citation 2} (:expected tally-detail)))
    (is (= {:tally/scholarly-citation 3} (:stored tally-detail)))))

(deftest mismatch-is-a-finding-not-a-zero
  ;; an out-of-window signal admits nothing: the replay is EMPTY relative to
  ;; the stored tallies — that is a BREAK, never a normalization to zero
  (let [[verdict rec] (accept [(sig {:at 5000 :hash "hx"})]
                              (stored {:tallies {:tally/scholarly-citation 5}
                                       :count 5}))]
    (is (= :accepted verdict))
    (is (false? (get-in rec [:lineage :all-match])))
    ;; signal was excluded out-of-window, enumerated — not silently dropped
    (is (= 1 (get-in rec [:replay :excluded-out-of-window-count])))
    (is (= 0 (get-in rec [:replay :admitted-count])))))

(deftest retraction-preservation-in-replay
  ;; retractions are tallied in the replay and never netted away
  (let [signals [(sig {:dim :retraction
                       :src :publisher-correction-or-retraction
                       :hash "r1"})
                 (sig {:hash "c1"})]
        [verdict rec] (accept
                       signals
                       (stored {:tallies {:tally/retraction 1
                                          :tally/scholarly-citation 1}
                                :count 2
                                :flags (assoc default-flags
                                              :retraction-present true
                                              :single-source-dependency false)}))]
    (is (= :accepted verdict))
    (is (true? (get-in rec [:lineage :all-match])))
    (is (true? (get-in rec [:flags :lineage-match])))))

;; ---------------------------------------------------------------------------
;; Values admission does not read are out of scope — re-valuing is inert
;; ---------------------------------------------------------------------------

(deftest payload-revaluing-is-inert
  (let [s1 [(sig) (sig {:hash "h2"})]
        s2 [(sig {:payload {:title "REVALUED"}})
            (sig {:hash "h2" :payload {:title "REVALUED"}})]
        [v1 l1] (accept s1 (stored))
        [v2 l2] (accept s2 (stored))]
    (is (= :accepted v1))
    (is (= :accepted v2))
    (is (= (pr-str (:lineage l1)) (pr-str (:lineage l2))))
    (is (= (pr-str (elo/dedupe-key l1)) (pr-str (elo/dedupe-key l2))))))

;; ---------------------------------------------------------------------------
;; Determinism
;; ---------------------------------------------------------------------------

(deftest determinism
  (let [signals [(sig {:hash "h1"}) (sig {:hash "h2" :at 1600})]
        run #(:lineage (nth (accept signals (stored)) 1))]
    (is (= (pr-str (run)) (pr-str (run))))))

;; ---------------------------------------------------------------------------
;; Append-only refresh history
;; ---------------------------------------------------------------------------

(deftest refresh-append-only
  (let [h0 []
        [_ l1] (accept [(sig)] (stored))
        [_ l2] (accept [(sig {:hash "h2"})] (stored))
        h1 (elo/refresh h0 l1)
        h2 (elo/refresh h1 l2)]
    (is (= 1 (count h1)))
    (is (= 2 (count h2)))
    ;; prior records are untouched — nothing rewritten
    (is (= l1 (first h2)))
    (is (= [l1 l2] (vec h2)))
    (is (= [l1 l2] (elo/history-records h2)))))

;; ---------------------------------------------------------------------------
;; Hyakka proposal / readback
;; ---------------------------------------------------------------------------

(deftest hyakka-proposal-and-readback
  (let [[verdict lineage] (accept [(sig) (sig {:hash "h2"})] (stored))
        proposal (elo/hyakka-proposal lineage)]
    (is (= :accepted verdict))
    (is (some? proposal))
    (is (= "evidence-lineage-observation/v1" (:proposal/contract proposal)))
    (is (some? (:proposal/dedupe-key proposal)))
    (is (true? (:proposal/replay-is-not-validation proposal)))
    (is (true? (:proposal/ranking-forbidden proposal)))
    (is (true? (:proposal/causal-claims-forbidden proposal)))
    (is (empty? (:proposal/claims proposal)))
    ;; faithful round-trip accepted
    (is (true? (elo/hyakka-readback-accept? proposal proposal)))
    ;; stripped structural guard refused
    (is (false? (elo/hyakka-readback-accept?
                 proposal (dissoc proposal :proposal/replay-is-not-validation))))
    (is (false? (elo/hyakka-readback-accept?
                 proposal (dissoc proposal :proposal/ranking-forbidden))))
    ;; injected ranking refused
    (is (false? (elo/hyakka-readback-accept?
                 proposal (assoc proposal :proposal/ranking [{:r 1}]))))
    ;; coverage upgraded to :complete refused
    (is (false? (elo/hyakka-readback-accept?
                 proposal (assoc proposal :proposal/coverage :complete))))
    ;; altered lineage verdict refused (break reported as match)
    (is (false? (elo/hyakka-readback-accept?
                 proposal (assoc-in proposal
                                    [:proposal/lineage :all-match]
                                    (not (get-in proposal
                                                 [:proposal/lineage :all-match]))))))
    ;; altered replay counts refused
    (is (false? (elo/hyakka-readback-accept?
                 proposal (update-in proposal [:proposal/replay :signal-count] inc))))
    ;; injected causal claims refused
    (is (false? (elo/hyakka-readback-accept?
                 proposal (assoc proposal :proposal/claims ["x supports y"]))))))

(deftest hyakka-nil-for-empty-replay
  ;; an empty replay never becomes a proposal — absence is not data
  (is (nil? (elo/hyakka-proposal nil))))

;; ---------------------------------------------------------------------------
;; Coverage flag
;; ---------------------------------------------------------------------------

(deftest coverage-always-partial
  (let [[verdict lineage] (accept [(sig)] (stored))]
    (is (= :accepted verdict))
    (is (= :partial (get-in lineage [:flags :coverage])))))

;; ---------------------------------------------------------------------------
;; Runner
;; ---------------------------------------------------------------------------

(defn -main []
  (let [{:keys [fail error]} (run-tests)]
    (when (or (pos? fail) (pos? error))
      (js/process.exit 1))))
