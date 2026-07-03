# cloud-itonami-6511

Open Business Blueprint for **ISIC Rev.5 6511**: life insurance. This
repository publishes a life-insurance underwriting/policy-binding
execution actor as an OSS business that any qualified, licensed operator
can fork, deploy, run, improve and sell.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
[`cloud-itonami-M6910`](https://github.com/cloud-itonami/cloud-itonami-M6910)
(Registrar-LLM ⊣ RegistrarGovernor), [`cloud-itonami-L6810`](https://github.com/cloud-itonami/cloud-itonami-L6810)
(Realtor-LLM ⊣ RealtorGovernor) and [`cloud-itonami-6310`](https://github.com/cloud-itonami/cloud-itonami-6310)
(HR-LLM ⊣ PolicyGovernor). Here it is **Underwriter-LLM ⊣ UnderwritingGovernor**.

> **Why an actor layer at all?** An LLM is great at drafting an
> underwriting-document checklist, normalizing application intake and
> flagging a thin KYC file -- but it has **no notion of which
> jurisdiction's insurance-regulator requirements are official, no
> license to underwrite, and no business being the one that decides real
> life-insurance coverage is issued today**. Letting it bind a policy
> directly invites fabricated underwriting requirements, laundering
> sanctioned parties into coverage, and silent liability for whoever runs
> it. This project seals the Underwriter-LLM into a single node and wraps
> it with an independent **UnderwritingGovernor**, a human **approval
> workflow**, and an immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor drafts and governs a life-insurance underwriting workflow:
application intake, per-jurisdiction underwriting-document checklisting,
applicant/beneficiary KYC-sanctions screening, and a policy-binding
proposal. It does **not**, by itself, hold a license to underwrite
insurance in any jurisdiction, and it does not claim to. Whoever deploys
and operates a live instance (a licensed life insurer, an MGA's
underwriting ops team) supplies the jurisdiction-specific license, the
real KYC/AML program and the real actuarial-rate/policy-administration
integrations, and bears that jurisdiction's liability -- the software
supplies the governed, spec-cited, audited execution scaffold so that
operator does not have to build the compliance layer from scratch for
every new market.

### Actuation

**A real policy binding (issuing real life-insurance coverage) is never
autonomous, at any phase, by construction.** Two independent layers
enforce this (`underwriting.governor`'s `:actuation` high-stakes gate and
`underwriting.phase`'s phase table, which never puts `:policy/bind` in
any phase's `:auto` set) -- see `underwriting.phase`'s docstring and
`test/underwriting/phase_test.clj`'s `policy-bind-never-auto-at-any-phase`.
The actor may draft, check, screen and recommend; a human operator (a
licensed underwriter) is always the one who actually binds coverage.

## The core contract

```
applicant/beneficiary intake + jurisdiction facts (underwriting.facts, spec-cited)
        |
        v
   ┌──────────────┐   proposal      ┌───────────────────┐
   │ Underwriter- │ ─────────────▶ │ UnderwritingGovernor│  (independent system)
   │ LLM (sealed) │  + citations   │ spec-basis · KYC    │
   └──────────────┘                 └─────────┬──────────┘
                             commit ◀──────────┼──────────▶ hold (fabricated law;
                                 │                  │         sanctions hit;
                           record + ledger    escalate ─▶ 人間承認    incomplete documents;
                                                (ALWAYS for :policy/bind)  un-overridable)
```

**The Underwriter-LLM never binds a policy the UnderwritingGovernor would
reject, and never binds without a human sign-off.** Hard violations
(fabricated jurisdiction requirements / sanctions hit / incomplete
documents) force **hold** and *cannot* be approved past; a clean binding
proposal still always routes to a human.

## Run

```bash
clojure -M:dev:run     # walk one clean application + one HARD-hold case through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a medical-exam sample courier
and document-intake robot moves physical specimens and documents between
examiner and underwriter, under the actor, gated by the independent
**UnderwritingGovernor**. The governor never dispatches hardware itself;
`:high`/`:safety-critical` actions require human sign-off.

## Open business

This repository is not only source code. It is a public, forkable business
model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, UnderwritingGovernor, policy-binding draft record, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an open
business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`6511`). Related capability contracts (policy/premium/claim shapes) are
published as [`kotoba-lang/insurance`](https://github.com/kotoba-lang/insurance);
this actor's `underwriting.*` namespaces are a self-contained governed
implementation, the same relationship `cloud-itonami-L6810`'s `realty.*`
has to `kotoba-lang/property`.

## Layout

| File | Role |
|---|---|
| `src/underwriting/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + policy-binding history |
| `src/underwriting/registry.cljc` | Policy-binding draft records (no fabricated international check-digit standard -- see docstring) |
| `src/underwriting/facts.cljc` | Per-jurisdiction underwriting requirement catalog with an official spec-basis citation per entry, honest coverage reporting |
| `src/underwriting/underwriterllm.cljc` | **Underwriter-LLM Advisor** -- `mock-advisor` ‖ `llm-advisor`; intake/assessment/KYC/binding proposals |
| `src/underwriting/governor.cljc` | **UnderwritingGovernor** -- spec-basis · sanctions hold · document-complete · confidence floor · actuation gate |
| `src/underwriting/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted assess/screen → supervised (binding always human) |
| `src/underwriting/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/underwriting/sim.cljc` | demo driver |
| `test/underwriting/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage |

## Jurisdiction coverage (honest)

`underwriting.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `underwriting.facts/catalog` --
currently 4 seeded (JPN, USA-NY, GBR, DEU) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `underwriting.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to make
coverage look bigger.

## License

Code and implementation templates are AGPL-3.0-or-later.
