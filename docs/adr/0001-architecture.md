# ADR-0001: cloud-itonami-isic-6511 -- Underwriter-LLM as a contained intelligence node

- Status: Accepted (2026-07-03)
- Related: `cloud-itonami-M6910` ADR-0001 (Registrar-LLM ⊣ RegistrarGovernor,
  the pattern this ADR ports), `cloud-itonami-L6810` ADR-0001 (Realtor-LLM
  ⊣ RealtorGovernor, the most recent sibling port), `cloud-itonami-6310`
  (HR-LLM ⊣ PolicyGovernor), `robotaxi-actor` ADR-0001 (sealing an unsafe
  research model behind an independent governor), langgraph-clj ADR-0001
  (Pregel superstep + interrupt + Datomic checkpoint)
- Context: `cloud-itonami-isic-6511` published a business/operator-model
  blueprint (insurance coverage push) but stopped at `:blueprint`
  maturity -- no governed actor implementation. This ADR deepens it to
  `:implemented`, the 3rd instance of this pattern in the fleet.

## Problem

Life-insurance underwriting needs three different kinds of judgment:

1. **Jurisdiction underwriting correctness** -- are the required
   underwriting/disclosure documents based on an official insurance-
   regulator source?
2. **KYC/sanctions screening** -- does the applicant or a beneficiary
   match a sanctions/PEP list?
3. **Real actuation** -- actually binding a policy: an irreversible
   real-world act (coverage begins, premium obligations attach).

An LLM has no authority or grounding for any of these. The design problem
is therefore not "run underwriting with an LLM" but "seal the LLM inside
a trust boundary and layer requirement-authenticity, KYC/sanctions, audit
and human-approval on top of it, while structurally fixing real actuation
as human-only."

## Decision

### 1. Underwriter-LLM is sealed into the bottom node; it never binds directly

`underwriting.underwriterllm` returns exactly four kinds of proposal:
intake normalization, jurisdiction underwriting-document checklist,
KYC/sanctions screening, and policy-binding proposal. No proposal writes
the SSoT or issues real coverage directly.

### 2. OperationActor = langgraph-clj StateGraph, 1 run = 1 underwriting operation

`underwriting.operation/build` is the same StateGraph shape as
`cloud-itonami-M6910` / `cloud-itonami-L6810` / `cloud-itonami-6310`
(intake → advise → govern → decide → commit | hold | request-approval).
One graph run corresponds to one underwriting operation, with no
unbounded inner loop.

### 3. UnderwritingGovernor is a separate system from Underwriter-LLM

`underwriting.governor` has five checks: spec-basis · sanctions-hit ·
document-complete (HARD, un-overridable) + confidence-floor ·
actuation-gate (SOFT, human decides).

### 4. Real actuation is structurally always human-only (enforced by two independent layers)

`underwriting.governor`'s actuation gate (`:stake :actuation` always
escalates) and `underwriting.phase`'s phase table (`:policy/bind` is
never a member of any phase's `:auto` set) both prevent a real policy
binding from ever auto-committing. Neither depends on the other being
implemented correctly.

### 5. No fabricated international policy-number standard

Same discipline as `cloud-itonami-L6810`'s `realty.registry`: there is no
single international check-digit standard for a life-insurance policy
number (unlike ISO 17442 LEI for legal entities, which `formation.registry`
genuinely ports). `underwriting.registry` therefore does not invent one;
it validates required fields and assigns a jurisdiction-scoped sequence
number only.

### 6. Relationship to `kotoba-lang/insurance`

`kotoba-lang/insurance` (the blueprint-tier capability lib backing all 7
insurance ISIC classes) publishes pure policy/premium/claim/underwriting-
decision contracts with no governor or human-approval workflow.
`underwriting.*` is a self-contained governed implementation for this one
class -- the same relationship `cloud-itonami-L6810`'s `realty.*` has to
`kotoba-lang/property`. The two are not required to share code: the
capability lib is for operators who only need the data contracts; the
actor is for operators who want the full governed execution scaffold.

## Consequences

- (+) Life insurance gets the same governed, auditable-actor treatment as
  company incorporation (`cloud-itonami-M6910`), real estate
  (`cloud-itonami-L6810`) and HR (`cloud-itonami-6310`), without
  centralizing liability in one vendor -- any licensed insurer/MGA can
  fork and run their own instance.
- (+) The actuation invariant (governor + phase, two layers) is
  regression-tested by `test/underwriting/phase_test.clj`'s
  `policy-bind-never-auto-at-any-phase`.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by
  `test/underwriting/store_contract_test.clj`, the same `:db-api`-driven
  swap pattern `formation.store` / `realty.store` use.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA-NY, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `underwriting.facts/coverage`
  reports this honestly rather than claiming broader coverage.
- (-) Real actuarial-rate-table integration, real policy-administration-
  system integration, and real KYC/sanctions-screening provider
  integration are out of scope for this OSS actor -- each operator's
  responsibility.
- 24 tests / 103 assertions, lint clean.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Keep `cloud-itonami-isic-6511` at `:blueprint` only | ❌ | Leaves insurance without a single `:implemented` reference actor, unlike the real-estate/legal/HR peers |
| Require `kotoba.insurance` (the capability lib) directly from `underwriting.*` | ❌ | Neither `formation.*` nor `realty.*` require their sibling capability libs; keeping the actor self-contained matches the established pattern and avoids coupling the governed-execution tier to the pure-contract tier |
| Fabricate a global policy-number check-digit standard for conformance-test rigor | ❌ | No such standard exists for life-insurance policy numbers (unlike LEI); inventing one would be the same dishonesty the `:banking`/`:cae` placeholder fixes elsewhere in this fleet were meant to correct |
