(ns analytics.window-refresh-test
  (:require [clojure.test :refer [deftest is testing]]
            [analytics.window-refresh :as wr]))

(def subject {:kind :research-artifact :id "artifact-1"})

(def influence-obs
  {:contract "influence-observation/v1"
   :method-version "mv-1"
   :subject subject
   :window {:from 1000 :to 2000}
   :tallies {:tally/scholarly-citation 3}
   :admitted-count 3
   :flags {:coverage :partial}})

(def diversity-obs
  {:contract "provenance-diversity-observation/v1"
   :method-version "mv-1"
   :subject subject
   :window {:from 2000 :to 3000}
   :admitted-count 5
   :composition {}
   :corroboration {}
   :flags {:coverage :partial}})

(defn- influence-obs-at [from to n]
  (assoc influence-obs :window {:from from :to to} :admitted-count n))

(deftest recognition-test
  (testing "recognized vs unrecognized records are separated, never mixed"
    (let [bad {:contract "something-else/v9"
               :window {:from 1 :to 2} :admitted-count 1}
          bad2 {:contract "influence-observation/v1"
                :window {:from 5 :to 3} :admitted-count 1}
        {:keys [recognized unrecognized]} (wr/sort-records
                                           [diversity-obs bad bad2 influence-obs])]
      (is (= 2 (count recognized)))
      (is (= ["something-else/v9" "influence-observation/v1"]
             (map :contract unrecognized))))))

(deftest determinism-test
  (testing "byte-identical observation across runs and input order"
    (let [h1 [influence-obs diversity-obs]
          h2 [diversity-obs influence-obs]
          o1 (second (wr/build-observation "mv-1" h1 5000 subject))
          o2 (second (wr/build-observation "mv-1" h2 5000 subject))]
      (is (= (pr-str o1) (pr-str o2)))
      (is (= (pr-str (wr/dedupe-key o1)) (pr-str (wr/dedupe-key o2))))))

  (testing "dedupe key stable across runs"
    (let [o (second (wr/build-observation "mv-1" [influence-obs] 5000 subject))]
      (is (string? (wr/dedupe-key o)))
      (is (= (wr/dedupe-key o) (wr/dedupe-key o))))))

(deftest window-series-test
  (testing "additive per-window facts, sorted by window start"
    (let [h [influence-obs
             (influence-obs-at 500 900 2)
             diversity-obs]
          {:keys [recognized]} (wr/sort-records h)
          series (wr/derive-window-series (wr/sort-records h))]
      (is (= [500 1000 2000] (mapv #(-> % :window :from) recognized)))
      (is (= 3 (:window-count series)))
      (is (= 10 (:total-admitted-across-windows series))))))

(deftest gaps-and-overlaps-test
  (testing "gap between consecutive windows is named, never zeroed"
    ;; windows: [1000,2000] and [2500,3000] -> gap of 500
    (let [h [influence-obs (influence-obs-at 2500 3000 4)]
          {:keys [recognized]} (wr/sort-records h)
          series (wr/derive-window-series (wr/sort-records h))
          flags (:flags (second (wr/build-observation "mv-1" h 4000 subject)))]
      (is (= {:window-gap 1} (wr/derive-gaps series)))
      (is (= [:window-gap] (mapv :kind (:discontinuities
                                       (second
                                        (wr/build-observation "mv-1" h 4000 subject))))))
      (is (true? (:window-gaps-present flags)))
      (is (= [2000 2500] (-> (second (wr/build-observation "mv-1" h 4000 subject))
                             :discontinuities first :between)))))

  (testing "overlapping windows are flagged as overlap, not merged"
    (let [h [influence-obs (influence-obs-at 1500 2500 4)]
          {:keys [recognized]} (wr/sort-records h)
          series (wr/derive-window-series (wr/sort-records h))]
      (is (= {:window-overlap 1} (wr/derive-gaps series)))
      (is (= 500 (:size (first (wr/derive-discontinuities series))))))))

(deftest staleness-test
  (testing "staleness is relative to caller-supplied as-of; no clock in module"
    (let [o (second (wr/build-observation "mv-1" [influence-obs] 5000 subject))]
      (is (= {:kind :measured :measured-through 2000 :unmeasured-span 3000}
             (-> o :flags :staleness)))))

  (testing "empty history is :no-observations, never a zero span"
    (let [o (second (wr/build-observation "mv-1" [] 5000 subject))]
      (is (= {:kind :no-observations :measured-through nil :unmeasured-span nil}
             (-> o :flags :staleness)))
      (is (zero? (:window-count o)))))

  (testing "missing as-of is refused structurally"
    (is (= [:rejected :missing-or-invalid-as-of]
           (wr/build-observation "mv-1" [influence-obs] nil subject)))
    (is (= [:rejected :missing-or-invalid-as-of]
           (wr/build-observation "mv-1" [influence-obs] "now" subject)))))

(deftest structural-refusals-test
  (testing "no trend / growth-rate / ranking / causal claims are ever written"
    (let [o (second (wr/build-observation "mv-1" [influence-obs] 5000 subject))]
      (is (nil? (:trend o)))
      (is (true? (:trend-forbidden o)))
      (is (nil? (:growth-rate o)))
      (is (true? (:growth-rate-forbidden o)))
      (is (nil? (:ranking o)))
      (is (true? (:ranking-forbidden o)))
      (is (true? (:causal-claims-forbidden o)))
      (is (empty? (:claims o)))
      (is (= :partial (-> o :flags :coverage)))))

  (testing "malformed history is refused"
    (is (= [:rejected :history-not-sequential]
           (wr/build-observation "mv-1" "not-a-history" 5000 subject)))))

(deftest missingness-test
  (testing "unrecognized records are enumerated, not averaged in"
    (let [bad {:contract "mystery/v1"}
          o (second (wr/build-observation "mv-1" [influence-obs bad] 5000 subject))
          flags (:flags o)]
      (is (= 1 (:unrecognized-record-count flags)))
      (is (= [{:contract "mystery/v1"}] (:unrecognized-records flags)))
      (is (= 1 (:window-count o))))))

(deftest refresh-history-test
  (testing "append-only: prior records immutable"
    (let [o1 (second (wr/build-observation "mv-1" [influence-obs] 5000 subject))
          h1 (wr/refresh [] o1)
          h2 (wr/refresh h1 (assoc o1 :as-of 6000))]
      (is (= 1 (count h1)))
      (is (= 2 (count h2)))
      (is (= o1 (first h2)))
      (is (= (wr/history-records h2) h2)))))

(deftest hyakka-test
  (testing "empty history proposes nothing (absence is not zero)"
    (let [o (second (wr/build-observation "mv-1" [] 5000 subject))]
      (is (nil? (wr/hyakka-proposal o)))))

  (testing "proposal carries refusal guards; readback acceptance round-trips"
    (let [o (second (wr/build-observation "mv-1" [influence-obs] 5000 subject))
          p (wr/hyakka-proposal o)]
      (is (some? p))
      (is (true? (:proposal/trend-forbidden p)))
      (is (nil? (:proposal/trend p)))
      (is (true? (:proposal/growth-rate-forbidden p)))
      (is (wr/hyakka-readback-accept? p p))))

  (testing "tampered readback refused"
    (let [o (second (wr/build-observation "mv-1" [influence-obs] 5000 subject))
          p (wr/hyakka-proposal o)]
      (is (not (wr/hyakka-readback-accept? p
                   (assoc p :proposal/trend-forbidden false))))
      (is (not (wr/hyakka-readback-accept? p
                   (assoc p :proposal/trend :rising))))
      (is (not (wr/hyakka-readback-accept? p
                   (assoc p :proposal/window-count 99))))
      (is (not (wr/hyakka-readback-accept? p
                   (assoc-in p [:proposal/flags :coverage] :complete))))
      (is (not (wr/hyakka-readback-accept? p
                   (assoc p :proposal/claims [{:text "impact is growing"}]))))
      (is (not (wr/hyakka-readback-accept? p
                   (dissoc p :proposal/dedupe-key)))))))

(deftest readback-surface-test
  (testing "unconfigured deploy: absence is not a measurement"
    (let [[tag payload] (wr/configure-observation nil)]
      (is (= :not-configured tag))
      (is (string? (:note payload)))
      (is (re-find #"not a measurement" (:note payload)))))

  (testing "invalid shapes are refused, not guessed at"
    (is (= [:invalid :not-a-json-object] (wr/configure-observation "x")))
    (is (= [:invalid :not-a-window-refresh-observation/v1-record]
           (wr/configure-observation {})))
    (is (= [:invalid :not-a-window-refresh-observation/v1-record]
           (wr/configure-observation {:contract "window-refresh-observation"
                                      :version "v2"
                                      :as-of 5000
                                      :window-series []})))
    (is (= [:invalid :not-a-window-refresh-observation/v1-record]
           (wr/configure-observation {:contract "window-refresh-observation"
                                      :version "v1"
                                      :as-of "5000"
                                      :window-series []}))))

  (testing "a record this contract built round-trips verbatim"
    (let [o (second (wr/build-observation "mv-1" [influence-obs] 5000 subject))
          [tag served] (wr/configure-observation o)]
      (is (= :ok tag))
      (is (= o served))
      (is (true? (:trend-forbidden served)))
      (is (true? (:ranking-forbidden served)))
      (is (= :partial (get-in served [:flags :coverage]))))))
