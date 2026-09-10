(ns analytics.attribution-observation-test
  (:require [clojure.test :refer [deftest is testing]]
            [analytics.attribution-observation :as ao]))

(def window {:from 1000 :to 2000})

(defn- signal [url & {:as over}]
  (merge {:dimension :policy-citation
          :source-url url
          :observed-at 1500
          :source/source-class :official-policy-document}
         over))

(def authority
  {:official-policy-document #{"meti.go.jp" "iso.org"}
   :standards-body-first-party #{"iso.org"}})

(deftest declared-attribution-test
  (testing "first-party signal under a declared authority domain is verified"
    (let [[_ obs] (ao/build-observation
                   "mv-1" window authority
                   [(signal "https://www.meti.go.jp/policy/energy/whitepaper.pdf")])]
      (is (= 1 (:admitted-count obs)))
      (is (= 1 (get-in obs [:flags :authority-class-counts :authority-declared])))
      (is (= 0 (get-in obs [:flags :authority-class-counts :authority-mismatch])))
      (is (= [{:source-url "https://www.meti.go.jp/policy/energy/whitepaper.pdf"
               :observed-at 1500}]
             (:authority-declared-signals obs))))))

(deftest subdomain-suffix-match-test
  (testing "dot-boundary suffix matches (jtc01.iso.org under iso.org)"
    (let [[_ obs] (ao/build-observation
                   "mv-1" window authority
                   [(signal "https://jtc01.iso.org/std" :source/source-class
                            :standards-body-first-party)])]
      (is (= 1 (get-in obs [:flags :authority-class-counts :authority-declared])))))

  (testing "substring without dot boundary does NOT match (notiso.org)"
    (let [[_ obs] (ao/build-observation
                   "mv-1" window authority
                   [(signal "https://notiso.org/std" :source/source-class
                            :standards-body-first-party)])]
      (is (= 1 (get-in obs [:flags :authority-class-counts :authority-mismatch]))))))

(deftest mismatch-test
  (testing "first-party claim under an undeclared host is a mismatch, kept whole"
    (let [[_ obs] (ao/build-observation
                   "mv-1" window authority
                   [(signal "https://blog.example.org/standard-copy")])]
      (is (= 1 (get-in obs [:flags :authority-class-counts :authority-mismatch])))
      (is (= [{:source-url "https://blog.example.org/standard-copy"
               :observed-at 1500}]
             (:authority-mismatch-signals obs))))))

(deftest undeclared-class-test
  (testing "first-party signal with no declared domain for its class is flagged, never silently sound"
    (let [[_ obs] (ao/build-observation
                   "mv-1" window {:official-policy-document #{}}
                   [(signal "https://www.meti.go.jp/policy/x.pdf")
                    (signal "https://iso.org/std" :source/source-class
                            :standards-body-first-party)])]
      ;; an empty declaration AND a missing declaration are both undeclared-class
      (is (= 2 (get-in obs [:flags :authority-class-counts :authority-undeclared-class])))
      (is (= 2 (count (:authority-undeclared-class-signals obs)))))))

(deftest no-authority-map-test
  (testing "an empty authority map is missingness, not soundness"
    (let [[_ obs] (ao/build-observation "mv-1" window {} [(signal "https://a.go.jp/x")])]
      (is (true? (get-in obs [:flags :no-authority-map-configured])))
      (is (true? (get-in obs [:flags :no-authority-map-is-not-sound])))
      (is (= 1 (get-in obs [:flags :authority-class-counts :authority-undeclared-class]))))))

(deftest third-party-not-applicable-test
  (testing "third-party classes are enumerated not-applicable, never attributed"
    (let [[_ obs] (ao/build-observation
                   "mv-1" window {:official-policy-document #{"nobody.example"}}
                   [(signal "https://registry.example/s1" :source/source-class
                            :citation-registry)])]
      (is (= 1 (get-in obs [:flags :not-applicable-count])))
      (is (= 1 (count (:not-applicable-signals obs))))
      ;; the third-party signal is never attributed even though a class IS declared
      (is (= {:authority-declared 0 :authority-mismatch 0
              :authority-undeclared-class 0}
             (get-in obs [:flags :authority-class-counts])))
      (is (= 0 (count (:authority-declared-signals obs)))))))

(deftest unparseable-host-test
  (testing "a URL without :// parses as no host — mismatch, never a match"
    (let [[_ obs] (ao/build-observation
                   "mv-1" window authority
                   [(signal "meti.go.jp/policy.pdf")])]
      (is (= 1 (get-in obs [:flags :authority-class-counts :authority-mismatch]))))))

(deftest rejection-test
  (testing "malformed inputs are refused, never coerced"
    (is (= [:rejected :admitted-not-vector]
           (ao/build-observation "mv-1" window authority (signal "https://a.go.jp/x"))))
    (is (= [:rejected :authority-not-a-map]
           (ao/build-observation "mv-1" window "authority" [])))
    (is (= [:rejected :malformed-window]
           (ao/build-observation "mv-1" {:from 2000 :to 1000} authority [])))
    (is (= [:rejected :non-conformant-signal-in-batch]
           (ao/build-observation "mv-1" window authority
                                 [(signal "https://a.go.jp/x" :dimension :vibes)])))))

(deftest window-accepted-test
  (testing "a well-formed window is accepted (empty batch still records)"
    (let [[tag obs] (ao/build-observation "mv-1" window authority [])]
      (is (= :accepted tag))
      (is (= "attribution-observation" (:contract obs)))
      (is (= 0 (:admitted-count obs))))))

(deftest structural-refusals-test
  (testing "no trust ranking, no severity, no people-level inference, ever"
    (let [[_ obs] (ao/build-observation "mv-1" window authority
                                        [(signal "https://www.meti.go.jp/x")])]
      (is (nil? (:severity-score obs)))
      (is (true? (:severity-score-forbidden obs)))
      (is (nil? (:trust-ranking obs)))
      (is (true? (:trust-ranking-forbidden obs)))
      (is (nil? (:ranking obs)))
      (is (true? (:ranking-forbidden obs)))
      (is (true? (:causal-claims-forbidden obs)))
      (is (empty? (:claims obs)))
      (is (= :partial (get-in obs [:flags :coverage]))))))

(deftest determinism-test
  (testing "byte-identical pr-str across runs"
    (let [a (second (ao/build-observation
                     "mv-1" window authority
                     [(signal "https://a.example/x") (signal "https://b.example/y")]))
          b (second (ao/build-observation
                     "mv-1" window authority
                     [(signal "https://b.example/y") (signal "https://a.example/x")]))]
      (is (= (pr-str a) (pr-str b))))))

(deftest refresh-history-test
  (testing "append-only refresh: prior records immutable"
    (let [o1 (second (ao/build-observation "mv-1" window authority []))
          o2 (second (ao/build-observation "mv-2" {:from 2000 :to 3000} authority []))
          h (ao/refresh (ao/refresh [] o1) o2)]
      (is (= [o1 o2] (ao/history-records h)))
      (is (= o1 (first (ao/history-records h)))))))

(deftest hyakka-roundtrip-test
  (testing "proposal and readback accept only the untouched record"
    (let [obs (second (ao/build-observation "mv-1" window authority
                                            [(signal "https://www.meti.go.jp/x")
                                             (signal "https://blog.example.org/y")]))
          proposal (ao/hyakka-proposal obs)]
      (is (some? proposal))
      (is (ao/hyakka-readback-accept? proposal
                                      (assoc proposal :proposal/dedupe-key
                                             (ao/dedupe-key obs))))
      ;; tampered readback: strip a structural refusal → refuse
      (is (not (ao/hyakka-readback-accept? proposal
                                           (assoc proposal
                                                  :proposal/severity-score-forbidden false))))
      ;; tampered readback: delete the mismatch signals → refuse
      (is (not (ao/hyakka-readback-accept? proposal
                                           (assoc proposal
                                                  :proposal/authority-mismatch-signals []))))))

  (testing "nothing admitted → no proposal"
    (is (nil? (ao/hyakka-proposal
               (second (ao/build-observation "mv-1" window authority []))))))

  (testing "empty authority map but signals admitted IS proposed"
    (is (some? (ao/hyakka-proposal
                (second (ao/build-observation "mv-1" window {}
                                              [(signal "https://a.go.jp/x")])))))))

(deftest readback-surface-test
  (testing "configure-observation tagged results"
    (is (= :not-configured (first (ao/configure-observation nil))))
    (is (= :invalid (first (ao/configure-observation "junk"))))
    (is (= :invalid
           (first (ao/configure-observation {:contract "attribution-observation"
                                             :version "v2"
                                             :window window
                                             :admitted-count 0
                                             :authority-mismatch-signals []}))))
    (let [obs {:contract "attribution-observation" :version "v1"
               :window window :admitted-count 1
               :authority-mismatch-signals []
               :flags {:missing-is-unmeasured true
                       :coverage :partial}}]
      (is (= [:ok obs] (ao/configure-observation obs))))))
