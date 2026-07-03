(ns underwriting.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and the
  Datomic-backed (langchain.db) store satisfy the same contract is what
  makes 'swap the SSoT for Datomic / kotoba-server' a configuration
  change, not a rewrite -- see `formation.store-contract-test` /
  `realty.store-contract-test` for the same pattern on the other actors
  in this family."
  (:require [clojure.test :refer [deftest is testing]]
            [underwriting.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "party-1" (:insured (store/application s "app-1"))))
      (is (= "JPN" (:jurisdiction (store/application s "app-1"))))
      (is (= ["party-2"] (:beneficiaries (store/application s "app-1"))))
      (is (= "田中 花子" (:name (store/party s "party-1"))))
      (is (false? (:sanctions-hit? (store/party s "party-1"))))
      (is (true? (:sanctions-hit? (store/party s "party-3"))))
      (is (= ["app-1" "app-2"] (mapv :id (store/all-applications s))))
      (is (nil? (store/kyc-of s "party-1")))
      (is (nil? (store/assessment-of s "app-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/binding-history s)))
      (is (zero? (store/next-sequence s "JPN"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :application/upsert
                                 :value {:id "app-1" :status :ready}})
        (is (= :ready (:status (store/application s "app-1"))))
        (is (= "party-1" (:insured (store/application s "app-1"))) "insured preserved"))
      (testing "assessment / kyc payloads commit and read back"
        (store/commit-record! s {:effect :assessment/set :path ["app-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/assessment-of s "app-1")))
        (store/commit-record! s {:effect :kyc/set :path ["party-1"]
                                 :payload {:party-id "party-1" :verdict :clear}})
        (is (= {:party-id "party-1" :verdict :clear} (store/kyc-of s "party-1"))))
      (testing "binding drafts a policy record and advances the sequence"
        (store/commit-record! s {:effect :policy/mark-bound :path ["app-1"]})
        ;; binding-history holds the inner "record" sub-map (registry/append's
        ;; convention), whose policy-number key is "record_id".
        (is (= "JPN-00000000" (get (first (store/binding-history s)) "record_id")))
        (is (= "binding-draft" (get (first (store/binding-history s)) "kind")))
        (is (= :bound (:status (store/application s "app-1"))))
        (is (= 1 (count (store/binding-history s))))
        (is (= 1 (store/next-sequence s "JPN"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/application s "nope")))
    (is (= [] (store/all-applications s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/binding-history s)))
    (is (zero? (store/next-sequence s "JPN")))
    (store/with-applications s {"x" {:id "x" :insured "p" :jurisdiction "JPN"
                                     :beneficiaries [] :coverage-amount 0 :currency "JPY"
                                     :status :intake}})
    (is (= "p" (:insured (store/application s "x"))))))
