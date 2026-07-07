# `underwriting_decision.kotoba` -- WASM-compiled governor reduction

A `.kotoba` (kotoba-lang/kotoba's capability-safe WASM subset) module that
runs under `kotoba-lang/kototama`'s `actor:host` ABI
(`kototama.tender`/`kototama.contract`, ADR-2607062330/2607062400),
including the new `llm-infer` host capability (`llm/infer`, id 225 in
`kotoba-core-contracts`' `capability_contract.edn`).

**Read `underwriting_decision.kotoba`'s own header comment first** -- it is
the authoritative scope statement. In short: this is a faithful reduction
of `src/underwriting/governor.cljc`'s `check` logic to the `.kotoba`
minimal subset, **not** a replacement for the governor, the Store, or the
`langgraph-clj` actor. It has no map/keyword/Store access; every fact the
real governor would look up from `underwriting.store` is instead a scalar
flag the host caller writes into the guest's own linear memory before
calling `main` (see the Input ABI below).

## Language-subset finding worth flagging

The background research for this task (and this workspace's CLAUDE.md)
describes `.kotoba` as `def`/`defn`/`ns`/`if`/`when`/`let`/`do`/
arithmetic/comparison/`and`/`or`/`not` + basic string ops + recursion.
Empirically, against the current `kotoba-lang/kotoba` `kotoba wasm emit`
backend (`kotoba.runtime/compile-wasm-expr`), **`and`/`or`/`when` are
recognized by the source-safety checker (`runtime/check` reports
`:kotoba.runtime/ok? true`) but are NOT implemented by the WASM
code-generator** -- `kotoba wasm emit` fails each with
`{:kotoba.wasm/problem "unsupported-op", :kotoba.wasm/op "and"}` (`"or"`,
`"when"` respectively). Verified directly against three minimal
single-form fixtures before writing this module. `if`/`let`/`do`, the
arithmetic/comparison ops, and `not` (which does compile, to `i32.eqz`)
all work as expected. This module is therefore written with nested `if`
only -- every AND/OR the governor logic needs is expressed as sequential
nested `if`s instead of a boolean-combinator form.

## Input ABI

`kototama.tender/call-main` always invokes the guest's exported `main`
with zero arguments (there is no other calling convention), so per-run
input is five bytes the host writes into the instance's own linear memory
at offsets 0-4 **before** calling `main` (well below `heap-base` 2048 and
below where any string literal is placed, so there is no collision):

| Offset | Field | Values | Mirrors |
|---|---|---|---|
| 0 | `has-spec-basis?` | 0/1 | `underwriting.governor/spec-basis-violations` |
| 1 | `sanctions-hit?` | 0/1 | `underwriting.governor/sanctions-violations` |
| 2 | `docs-complete?` | 0/1 | `underwriting.governor/document-violations` |
| 3 | `confidence-x100` | 0..100 | `(:confidence proposal)` * 100 (no floats in this subset; the governor's `confidence-floor` 0.6 is literal `60` here) |
| 4 | `is-actuation?` | 0/1 | `underwriting.governor/high-stakes` (`:stake :actuation`) |

## Output

`main`'s i64 result is a decision code: `0` = ok/auto, `1` = escalate
(advisor consulted via `llm-infer`), `2` = hold (HARD violation --
structurally un-overridable, mirrors the governor never asking the
advisor to relitigate a fabricated-law/sanctions/incomplete-docs hold, so
no `llm-infer` call happens on that path at all). Every path also
`log-write`s one audit line: a fixed `HOLD:*`/`AUTO-OK` marker, the
`llm-infer` reply bytes, or a fixed `ESCALATE:*:llm-unavailable` marker
when `llm-infer` fails closed (-1: no API key configured on the tender
host, or the Anthropic call itself failed).

Because `str-ptr`/`str-len` only accept compile-time string literals (no
runtime string concatenation exists in this subset), the two prompts sent
to `llm-infer` are fixed literals selected by which escalation branch is
taken (`is-actuation?` vs. low confidence) -- not a template filled in
with the actual confidence value.

## Compile

```sh
# from kotoba-lang/kotoba, with kotoba-core-contracts' llm/infer capability
# (id 225) on the classpath -- ahead of any older cached copy, since
# `-Sdeps :override-deps` alone did not take effect against the git-lib
# cache in testing; prepending src+resources to an explicit classpath did:
java -cp "<kotoba-core-contracts>/src:<kotoba-core-contracts>/resources:$(clojure -Spath)" \
  clojure.main -m kotoba.launcher wasm emit wasm/underwriting_decision.kotoba \
  --policy wasm/underwriting_decision_policy.edn \
  --output wasm/underwriting_decision.wasm --json
```

## Verified locally (this PR)

- `kotoba wasm emit` compiles the module: 918 bytes, 8 data segments (the
  8 distinct string literals), 2 host imports (`log_write`, `llm_infer`),
  `main` exported with `i32` result (Chicory widens this to the `long[]`
  `kototama.tender/call-main` expects, same as the existing
  `kotoba-compiled-sha256-hex` fixture in `kotoba-lang/kototama`).
- Loaded and run through `kototama.tender/instantiate`/`call-main` (real
  Chicory `Instance`, not a mock) across 8 scenarios poking the 5 input
  bytes directly into the instance's memory before calling `main`:
  auto-ok; all three HOLD sub-cases (no-spec-basis / sanctions-hit /
  incomplete-documents); low-confidence escalate with both a DI-mocked
  `llm-infer` reply and a mocked nil (fail-closed) reply; actuation
  escalate with a DI-mocked reply; and actuation escalate through the
  REAL (unmocked) `kototama.tender/default-llm-client`, which correctly
  returned `-1` (no `ANTHROPIC_API_KEY`/etc. configured in this
  environment) and the module logged the fail-closed
  `ESCALATE:actuation:llm-unavailable` marker. 8/8 passed. This is
  wiring-level verification only -- **no live Anthropic API call was
  made or could be made in this environment** (no API key available);
  actual model-response quality is unverified.
