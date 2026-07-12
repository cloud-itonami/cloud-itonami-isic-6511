(ns underwriting.governor-contract-test
  "The governor contract as executable tests -- the life-insurance analog
  of `cloud-itonami-M6910`'s `formation.governor-contract-test` /
  `cloud-itonami-L6810`'s `realty.governor-contract-test` / robotaxi's
  safety_contract_test / gftd-talent-actor's policy_contract_test. The
  single invariant under test:

    Underwriter-LLM never binds a policy the UnderwritingGovernor would
    reject, `:policy/bind` NEVER auto-commits at any phase, and every
    decision (commit OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [underwriting.store :as store]
            [underwriting.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :underwriter :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :application/intake :subject "app-1"
                   :patch {:id "app-1" :status :ready}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :ready (:status (store/application db "app-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest jurisdiction-assess-always-needs-approval
  (testing "assess is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :jurisdiction/assess :subject "app-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (g/run* actor {:approval {:status :approved :by "op-1"}}
                       {:thread-id "t2" :resume? true})]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "app-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a jurisdiction/assess proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :jurisdiction/assess :subject "app-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "app-1")) "no assessment written"))))

(deftest sanctions-hit-is-held-and-unoverridable
  (testing "a sanctions/PEP hit on a party -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :kyc/screen :subject "party-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:sanctions-hit} (-> (store/ledger db) first :basis)))
      (is (nil? (store/kyc-of db "party-3")) "no KYC clearance written"))))

(deftest bind-without-assessment-is-held
  (testing "policy/bind before any jurisdiction assessment -> HOLD (incomplete documents)"
    (let [[db actor] (fresh)
          res (exec-op actor "t5" {:op :policy/bind :subject "app-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:incomplete-documents} (-> (store/ledger db) first :basis))))))

(deftest policy-bind-always-escalates-then-human-decides
  (testing "a clean, fully-assessed binding still ALWAYS interrupts for human approval -- actuation is never auto"
    (let [[db actor] (fresh)
          _ (exec-op actor "t6a" {:op :jurisdiction/assess :subject "app-1"} operator)
          _ (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id "t6a" :resume? true})
          _ (exec-op actor "t6b" {:op :kyc/screen :subject "party-1"} operator)
          _ (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id "t6b" :resume? true})
          r1 (exec-op actor "t6" {:op :policy/bind :subject "app-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, policy-binding record drafted"
        (let [r2 (g/run* actor {:approval {:status :approved :by "op-1"}}
                         {:thread-id "t6" :resume? true})]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= :bound (:status (store/application db "app-1"))))
          (is (= 1 (count (store/binding-history db))) "one draft binding record")))))
  (testing "reject -> hold, nothing bound"
    (let [[db actor] (fresh)
          _ (exec-op actor "t7a" {:op :jurisdiction/assess :subject "app-1"} operator)
          _ (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id "t7a" :resume? true})
          _  (exec-op actor "t7" {:op :policy/bind :subject "app-1"} operator)
          r2 (g/run* actor {:approval {:status :rejected :by "op-1"}}
                     {:thread-id "t7" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (empty? (store/binding-history db)) "nothing bound on reject"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :application/intake :subject "app-1"
                       :patch {:id "app-1" :status :ready}} operator)
      (exec-op actor "b" {:op :jurisdiction/assess :subject "app-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
