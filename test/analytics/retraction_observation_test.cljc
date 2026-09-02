(ns analytics.retraction-observation-test
  (:require [clojure.test :refer [deftest is testing]]
            [analytics.retraction-observation :as ro]))

(def subject {:kind :research-artifact :id "artifact-1"})

(def window {:from 1000 :to 2000})

(defn- signal [id & {:as over}]
  (merge {:dimension :scholarly-citation
          :source-url (str "https://registry.example/" id)
          :observed-at 1500
          :content-hash (str "hash-" id)
          :source/source-class :citation-registry}
         over))

(defn- notice [id & {:as over}]
  (merge {:notice/kind :retraction
          :source-url (str "https://publisher.example/retractions/" id)
          :observed-at 1600
          :content-hash (str "hash-" id)
          :source/source-class :publisher-correction-or-retraction}
         over))

(deftest exact-match-test
  (testing "retraction matched by exact content hash — affected signal kept, not deleted"
    (let [s (signal "s1")
          n (notice "s1")
          [_ obs] (ro/build-observation "mv-1" window [s] [n] subject)]
      (is (= 1 (:admitted-count obs)))
      (is (= 1 (:notice-count obs)))
      (is (= 1 (get-in obs [:flags :retracted-signal-count])))
      (is (= [{:content-hash "hash-s1" :dimension :scholarly-citation
               :observed-at 1500 :source-class :citation-registry}]
             (:retracted-signals obs)))
      (is (false? (get-in obs [:flags :no-notices-configured]))))))

(deftest url-match-test
  (testing "retraction matched by exact target URL when hashes differ"
    (let [s (signal "s1" :notice/target-url "https://doi.example/art")
          n (notice "n1" :content-hash "other-hash"
                    :notice/target-url "https://doi.example/art")
          [_ obs] (ro/build-observation "mv-1" window [s] [n] subject)]
      (is (= 1 (get-in obs [:flags :retracted-signal-count]))))))

(deftest no-fuzzy-match-test
  (testing "a notice for a different artifact does NOT match — and does not make others sound"
    (let [s1 (signal "s1") s2 (signal "s2")
          n (notice "s1")
          [_ obs] (ro/build-observation "mv-1" window [s1 s2] [n] subject)]
      (is (= 1 (get-in obs [:flags :retracted-signal-count])))
      (is (= 1 (count (:retracted-signals obs))))
      (is (nil? (:severity-score obs)))
      (is (true? (:severity-score-forbidden obs))))))

(deftest absence-is-not-soundness-test
  (testing "no notices configured is flagged, never read as 'no retractions'"
    (let [s (signal "s1")
          [_ obs] (ro/build-observation "mv-1" window [s] [] subject)]
      (is (true? (get-in obs [:flags :no-notices-configured])))
      (is (true? (get-in obs [:flags :no-notices-is-not-sound])))
      (is (= 0 (get-in obs [:flags :retracted-signal-count])))
      (is (true? (get-in obs [:flags :missing-is-unmeasured]))))))

(deftest out-of-scope-notice-test
  (testing "a matching notice observed outside the window is out-of-scope, not retracted"
    (let [s (signal "s1")
          n (notice "s1" :observed-at 2500)
          [_ obs] (ro/build-observation "mv-1" window [s] [n] subject)]
      (is (= 0 (get-in obs [:flags :retracted-signal-count])))
      (is (= 1 (get-in obs [:flags :notice-outside-scope-count])))
      (is (= [{:content-hash "hash-s1" :observed-at 2500}]
             (:notice-outside-scope-signals obs))))))

(deftest unrecognized-notice-test
  (testing "a notice from a forbidden source class is enumerated, never averaged in"
    (let [s (signal "s1")
          bad (notice "n1" :source/source-class :social-media-post
                      :content-hash "hash-s1")
          good (notice "s2")
          [_ obs] (ro/build-observation "mv-1" window [s] [bad good] subject)]
      ;; bad notice is NOT applied even though its hash matches
      (is (= 0 (get-in obs [:flags :retracted-signal-count])))
      (is (= 1 (get-in obs [:flags :unrecognized-notice-count])))
      (is (= [{:source-class :social-media-post}]
             (get-in obs [:flags :unrecognized-notices])))
      (is (= 1 (:notice-count obs))))))

(deftest exposure-per-dimension-test
  (testing "exposure is presence per dimension, additive, never a rate"
    (let [s1 (signal "s1" :dimension :scholarly-citation)
          s2 (signal "s2" :dimension :replication)
          n1 (notice "s1")
          n2 (notice "s2")
          [_ obs] (ro/build-observation "mv-1" window [s1 s2] [n1 n2] subject)]
      (is (= {:replication 1 :scholarly-citation 1}
             (get-in obs [:flags :retraction-exposure]))))))

(deftest rejection-test
  (testing "malformed inputs are refused, never coerced"
    (is (= [:rejected :admitted-not-vector]
           (ro/build-observation "mv-1" window (signal "s1") [] subject)))
    (is (= [:rejected :notices-not-sequential]
           (ro/build-observation "mv-1" window [] "notices" subject)))
    (is (= [:rejected :malformed-window]
           (ro/build-observation "mv-1" {:from 2000 :to 1000} [] [] subject)))
    (is (= [:rejected :non-conformant-signal-in-batch]
           (ro/build-observation "mv-1" window [(signal "s1" :content-hash nil)]
                                 [] subject)))))

(deftest structural-refusals-test
  (testing "no trust ranking, no severity, no people-level inference, ever"
    (let [s (signal "s1")
          n (notice "s1")
          [_ obs] (ro/build-observation "mv-1" window [s] [n] subject)]
      (is (nil? (:trust-ranking obs)))
      (is (true? (:trust-ranking-forbidden obs)))
      (is (nil? (:ranking obs)))
      (is (true? (:ranking-forbidden obs)))
      (is (true? (:causal-claims-forbidden obs)))
      (is (empty? (:claims obs)))
      (is (= :partial (get-in obs [:flags :coverage]))))))

(deftest determinism-test
  (testing "byte-identical pr-str across runs"
    (let [s1 (signal "s1") s2 (signal "s2")
          n1 (notice "s1") n2 (notice "s2")
          a (second (ro/build-observation "mv-1" window [s1 s2] [n1 n2] subject))
          b (second (ro/build-observation "mv-1" window [s2 s1] [n2 n1] subject))]
      (is (= (pr-str a) (pr-str b))))))

(deftest refresh-history-test
  (testing "append-only refresh: prior records immutable"
    (let [o1 (second (ro/build-observation "mv-1" window [] [] subject))
          o2 (second (ro/build-observation "mv-2" {:from 2000 :to 3000} [] [] subject))
          h (ro/refresh (ro/refresh [] o1) o2)]
      (is (= [o1 o2] (ro/history-records h)))
      (is (= o1 (first (ro/history-records h))))
      (is (= o2 (second (ro/history-records h)))))))

(deftest hyakka-roundtrip-test
  (testing "proposal and readback accept only the untouched record"
    (let [s (signal "s1")
          n (notice "s1")
          obs (second (ro/build-observation "mv-1" window [s] [n] subject))
          proposal (ro/hyakka-proposal obs)]
      (is (some? proposal))
      (is (ro/hyakka-readback-accept? proposal
                                      (assoc proposal :proposal/dedupe-key
                                             (ro/dedupe-key obs))))
      ;; tampered readback: strip a structural refusal → refuse
      (is (not (ro/hyakka-readback-accept? proposal
                                           (assoc proposal
                                                  :proposal/severity-score-forbidden false))))
      ;; tampered readback: delete the affected signals → refuse
      (is (not (ro/hyakka-readback-accept? proposal
                                           (assoc proposal :proposal/retracted-signals []))))))

  (testing "no notice stream configured and nothing admitted → no proposal"
    (is (nil? (ro/hyakka-proposal
               (second (ro/build-observation "mv-1" window [] [] subject))))))

  (testing "configured notice stream with zero matches IS proposed"
    (is (some? (ro/hyakka-proposal
                (second (ro/build-observation "mv-1" window [(signal "s1")]
                                             [(notice "nX")] subject)))))))

(deftest readback-surface-test
  (testing "configure-observation tagged results"
    (is (= :not-configured (first (ro/configure-observation nil))))
    (is (= :invalid (first (ro/configure-observation "junk"))))
    (is (= :invalid
           (first (ro/configure-observation {:contract "retraction-observation"
                                             :version "v2"
                                             :window window
                                             :notice-count 0
                                             :retracted-signals []}))))
    (let [obs {:contract "retraction-observation" :version "v1"
               :window window :notice-count 1 :retracted-signals []
               :flags {:missing-is-unmeasured true
                       :no-notices-is-not-sound true
                       :coverage :partial}}]
      (is (= [:ok obs] (ro/configure-observation obs))))))
