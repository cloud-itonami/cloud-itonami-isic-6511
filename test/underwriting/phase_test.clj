(ns underwriting.phase-test
  "The phase table as executable tests. The single invariant this repo
  cannot regress on: `:policy/bind` must NEVER be a member of any phase's
  `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [underwriting.phase :as phase]))

(deftest policy-bind-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real binding"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :policy/bind))
          (str "phase " n " must not auto-commit :policy/bind")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-intake
  (is (= #{:application/intake} (:auto (get phase/phases 3)))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :application/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :policy/bind} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :application/intake} :commit)))))
