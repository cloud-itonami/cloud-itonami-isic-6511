# Operator Quickstart

Get the life insurance blueprint running and validated on your machine in minutes.

## Prerequisites

- **Clojure 1.12+** (check: `clojure --version`)
- **Java 17+** (bundled with most Clojure installs; check: `java -version`)
- **Git** to clone the repository

No npm, Docker, or external services required for a dry-run. For LLM advisor features, you'll need an API key (mock-advisor mode works without one).

## Quick Start

### 1. Clone and enter the repo

```bash
git clone https://github.com/cloud-itonami/cloud-itonami-isic-6511.git
cd cloud-itonami-isic-6511
```

### 2. Run the demo (5–10 minutes)

The `:run` target walks a clean application and a hard-hold case through the UnderwritingGovernor:

```bash
clojure -M:dev:run
```

This:
- Drafts an underwriting proposal for applicant intake
- Screens the applicant against KYC/sanctions rules
- Applies jurisdiction-specific underwriting checklists
- Flags fabricated requirements and holds
- Records every decision to the audit ledger

Output appears in the terminal; a clean binding is proposed but never autonomously bound (human sign-off is always required by construction).

### 3. Validate the governor contract (3–5 minutes)

```bash
clojure -M:dev:test
```

This runs:
- **Governor contract** — spec-basis citations, KYC/sanctions screening, document completeness
- **Phase invariants** — ensures phase transitions are sound and `:policy/bind` is never autonomous
- **Store parity** — verifies in-memory and Datomic stores behave identically
- **Registry conformance** — policy binding records conform to the spec
- **Jurisdiction coverage** — reports how many requested jurisdictions have official sources in the catalog

If any test fails, check that Clojure 1.12+ and Java 17+ are installed, and all dependencies resolve.

### 4. Lint the code

```bash
clojure -M:lint
```

Runs clj-kondo to catch common Clojure mistakes. CI mirrors this, so fixing lint errors locally prevents CI flakes.

## Where the Governor Lives

The **UnderwritingGovernor** is the independent approval layer that seals the Underwriter-LLM advisor inside a governed workflow:

```
src/underwriting/governor.cljc
```

Key interfaces and checks:

| File | Purpose |
|---|---|
| `src/underwriting/governor.cljc` | Governor spec check, sanctions hold, document completeness, actuation gate |
| `src/underwriting/phase.cljc` | Phase state machine (0→3); phase 3 gates `:policy/bind` and requires human sign-off always |
| `src/underwriting/facts.cljc` | Per-jurisdiction underwriting requirements with spec citations (currently: JPN, USA-NY, GBR, DEU) |
| `src/underwriting/underwriterllm.cljc` | Underwriter-LLM advisor (mock or real); sealed; never binds policies autonomously |
| `src/underwriting/operation.cljc` | The OperationActor StateGraph that orchestrates phases and applies governance |
| `src/underwriting/store.cljc` | Audit ledger and immutable policy-binding history |

## Next Steps

### Run a production dry-run

1. Fork this repository to your organization account
2. Configure the UnderwritingGovernor's hold/escalation policy in your operator config (see `src/underwriting/governor.cljc`)
3. Import your historical policies against the store contract and validate record integrity
4. Run an internal operation (e.g., one real applicant) through the actor and audit the output
5. Certify your audit export (see `docs/operator-guide.md`)

### Bind a policy with human approval

Once you've validated the contract locally:

1. Deploy the actor to a runtime (Clojure, ClojureScript/Node, or WASM — see `wasm/README.md` for PoC)
2. Implement your jurisdiction-specific KYC/AML program and link it into `src/underwriting/underwriterllm.cljc`'s `:screen-kyc` override
3. Integrate your policy administration system (billing, claims, beneficiary tracking)
4. Always route `:policy/bind` decisions to a human underwriter; the governor will never approve a policy with hard violations (fabricated law, sanctions hit, incomplete docs)

## Jurisdiction Coverage

The catalog in `src/underwriting/facts.cljc` currently covers:
- Japan (JPN)
- United States — New York (USA-NY)
- Great Britain (GBR)
- Germany (DEU)

Adding a jurisdiction is additive: one map entry citing an official spec source. We do not fabricate coverage to make the list look bigger.

## Support & Questions

- **Operator onboarding**: See `docs/operator-guide.md` and `docs/business-model.md`
- **Architecture & decisions**: See `docs/adr/0001-architecture.md`
- **Issues**: File a GitHub issue with your error output and Clojure version
- **Commercial hosting & support**: Visit itonami.cloud for managed deployment options

## License

Code is AGPL-3.0-or-later. See LICENSE for full terms.
