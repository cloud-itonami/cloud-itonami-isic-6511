(ns underwriting.facts-test
  (:require [clojure.test :refer [deftest is]]
            [underwriting.facts :as facts]))

(deftest jpn-has-a-spec-basis
  (is (some? (facts/spec-basis "JPN")))
  (is (string? (:provenance (facts/spec-basis "JPN")))))

(deftest fra-has-a-spec-basis
  (is (some? (facts/spec-basis "FRA")))
  (is (string? (:provenance (facts/spec-basis "FRA"))))
  (is (string? (:legal-basis (facts/spec-basis "FRA"))))
  (is (seq (facts/doc-checklist "FRA")) "FRA must carry a non-empty required-docs checklist"))

(deftest unknown-jurisdiction-has-no-fabricated-spec-basis
  (is (nil? (facts/spec-basis "ATL"))))

(deftest coverage-never-reports-a-missing-jurisdiction-as-covered
  (let [report (facts/coverage ["JPN" "ATL" "GBR"])]
    (is (= 2 (:covered report)))
    (is (= ["ATL"] (:missing-jurisdictions report)))
    (is (= ["GBR" "JPN"] (:covered-jurisdictions report)))))

(deftest required-docs-satisfied-needs-every-doc
  (let [all (facts/doc-checklist "JPN")]
    (is (facts/required-docs-satisfied? "JPN" all))
    (is (not (facts/required-docs-satisfied? "JPN" (rest all))))
    (is (not (facts/required-docs-satisfied? "ATL" all)) "no spec-basis -> never satisfied")))
