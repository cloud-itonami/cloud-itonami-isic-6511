(ns underwriting.registry
  "Pure-function policy-binding record construction -- an append-only
  life-insurance policy issuance draft.

  Like `cloud-itonami-L6810`'s `realty.registry`, there is no single
  international check-digit standard for a life-insurance policy number --
  every insurer/jurisdiction assigns its own reference format. This
  namespace does NOT invent one; it builds a jurisdiction-scoped sequence
  number and validates the record's required fields, the same honest,
  non-fabricating discipline `underwriting.facts` uses.

  This namespace is pure data + pure functions -- no I/O, no network call
  to any insurer's core-administration system. It builds the RECORD an
  operator would keep, not the act of binding coverage itself (that is
  `underwriting.operation`'s `:policy/bind`, which is always human-gated
  -- see README `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  licensed underwriter's act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-binding
  "Validate + construct a life-insurance policy-binding registration DRAFT.
  Pure function -- does not touch any real insurer system or bind any
  real coverage."
  [insured beneficiaries coverage-amount jurisdiction sequence]
  (when-not (and insured (not= insured ""))
    (throw (ex-info "binding: insured required" {})))
  (when-not (seq beneficiaries)
    (throw (ex-info "binding: at least one beneficiary required" {})))
  (when (< coverage-amount 0)
    (throw (ex-info "binding: coverage-amount must be >= 0" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "binding: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "binding: sequence must be >= 0" {})))
  (let [policy-number (str (str/upper-case jurisdiction) "-" (zero-pad sequence 8))
        record {"record_id" policy-number
                "kind" "binding-draft"
                "insured" insured
                "beneficiaries" (vec beneficiaries)
                "coverage_amount" coverage-amount
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "policy_number" policy-number
     "certificate" (unsigned-certificate "PolicyBindingCertificate" policy-number policy-number)}))

(defn register-endorsement
  "Append-only endorsement draft (e.g. a post-binding beneficiary change).
  Never overwrites the binding record."
  [policy-number changed-fields effective-date]
  (when-not (and policy-number (not= policy-number ""))
    (throw (ex-info "endorsement: policy_number required" {})))
  (when-not (seq changed-fields)
    (throw (ex-info "endorsement: changed_fields required" {})))
  {"record" {"record_id" (str policy-number "#end@" effective-date)
             "kind" "endorsement-draft"
             "policy_number" policy-number
             "changed" (into {} changed-fields)
             "effective_date" effective-date
             "immutable" true}})

(defn append
  "Append a binding record, returning a NEW list (never mutate history in place)."
  [history result]
  (conj (vec history) (get result "record")))
