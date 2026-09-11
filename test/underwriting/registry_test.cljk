(ns underwriting.registry-test
  (:require [clojure.test :refer [deftest is]]
            [underwriting.registry :as r]))

(deftest certificate-is-a-draft-not-a-real-binding
  (let [result (r/register-binding "party-1" ["party-2"] 0 "JPN" 1)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest binding-assigns-policy-number
  (let [result (r/register-binding "party-1" ["party-2"] 50000000 "JPN" 7)]
    (is (= (get result "policy_number") "JPN-00000007"))
    (is (= (get-in result ["record" "immutable"]) true))
    (is (= (get-in result ["record" "kind"]) "binding-draft"))))

(deftest binding-validation-rules
  (let [bad-args [["" ["p"] 0 "JPN"]
                  ["insured" [] 0 "JPN"]
                  ["insured" ["p"] -1 "JPN"]
                  ["insured" ["p"] 0 ""]]]
    (doseq [[insured beneficiaries coverage jurisdiction] bad-args]
      (is (thrown? #?(:clj Exception :cljs js/Error)
                   (r/register-binding insured beneficiaries coverage jurisdiction 1))))))

(deftest endorsement-is-append-only
  (let [b (r/register-binding "party-1" ["party-2"] 0 "JPN" 1)
        hist (r/append [] b)
        end (r/register-endorsement (get b "policy_number") {"beneficiary" "new"} "2026-07-03")
        hist2 (r/append hist end)]
    (is (and (= (count hist) 1) (= (count hist2) 2)))
    (is (= (get-in hist2 [0 "kind"]) "binding-draft"))
    (is (= (get-in hist2 [1 "kind"]) "endorsement-draft"))))
