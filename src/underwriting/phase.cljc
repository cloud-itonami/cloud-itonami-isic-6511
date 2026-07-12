(ns underwriting.phase
  "Phase 0->3 staged rollout -- the life-insurance analog of
  `cloud-itonami-M6910`'s `formation.phase` / `cloud-itonami-L6810`'s
  `realty.phase`: start narrow (read-only), widen as trust grows. Where
  the UnderwritingGovernor answers 'is this allowed?', the phase answers
  'how much autonomy does the actor have *yet*?'. It can only ever make
  the actor MORE conservative than the governor, never the reverse.

    Phase 0  read-only        -- coverage/checklist reads only (still
                                 governor-gated). Shadow/observe.
    Phase 1  assisted-intake  -- application intake allowed, every write
                                 needs human approval.
    Phase 2  + assess/screen  -- adds jurisdiction underwriting
                                 assessment + KYC screening writes (still
                                 approval).
    Phase 3  supervised auto  -- governor-clean, high-confidence INTAKE
                                 writes may auto-commit. Assessment and
                                 KYC screening still escalate (a human
                                 should see a jurisdiction/party
                                 determination before it becomes the
                                 basis for a policy binding).

  `:policy/bind` (issuing real life-insurance coverage) is deliberately
  ABSENT from every phase's `:auto` set, including phase 3 -- this is a
  permanent structural fact about this table, not a rollout milestone
  still to come. A real policy binding is always a human call (a
  licensed underwriter); see README `Actuation`. The
  UnderwritingGovernor's `:actuation` high-stakes gate
  (underwriting.governor) enforces the same invariant independently --
  two layers, not one, agree on this.

  The decision core is delegated to the safety kernel
  `underwriting.kernels.gate` (integer-coded, fail-closed, safe-kotoba
  subset); this namespace keeps the human-readable phase table (the
  documentation and structural-invariant tests read it) and does the
  keyword<->wire-code mapping at the boundary. The kernel's own battery
  and the parity matrix in `underwriting.kernels.gate-test` pin the two
  representations together."
  (:require [underwriting.kernels.gate :as kernel]))

(def read-ops  #{:coverage/report})
(def write-ops #{:application/intake :jurisdiction/assess :kyc/screen :policy/bind})

;; NOTE the invariant: :policy/bind is a member of `write-ops` (it is
;; governor-gated like any write) but is NEVER a member of any phase's
;; `:auto` set below. Do not add it there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"          :writes #{}                                                :auto #{}}
   1 {:label "assisted-intake"    :writes #{:application/intake}                             :auto #{}}
   2 {:label "assisted-assess"    :writes #{:application/intake :jurisdiction/assess :kyc/screen} :auto #{}}
   3 {:label "supervised-auto"    :writes write-ops                                          :auto #{:application/intake}}})

(def default-phase 3)

;; ---- kernel wire-code bridges (façade-side, not kernel vocabulary) ----

(defn- op->code
  "Kernel op wire code. Unknown ops map to 5 (unknown write) — the
  kernel never write-enables it, so an unrecognized op fails closed to
  HOLD exactly as the old set-membership logic did."
  [op]
  (cond
    (contains? read-ops op)      0
    (= op :application/intake)   1
    (= op :jurisdiction/assess)  2
    (= op :kyc/screen)           3
    (= op :policy/bind)          4
    :else                        5))

(defn- disposition->code [d]
  (cond (= d :commit) 0 (= d :escalate) 1 (= d :hold) 2 :else 2))

(defn- code->disposition [c]
  (if (= c 0) :commit (if (= c 1) :escalate :hold)))

(defn- code->reason [c]
  (if (= c 1) :phase-disabled (if (= c 2) :phase-approval nil)))

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - reads pass through unchanged (phase restricts autonomy, not reads).
  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:policy/bind` is never auto-eligible at any phase, so it always
    escalates once the governor clears it (or holds if the governor
    doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [p (if (contains? phases phase) phase default-phase)
        op-code (op->code op)
        gov-code (disposition->code governor-disposition)
        d (kernel/phase-disposition p op-code gov-code)
        r (kernel/phase-reason p op-code gov-code)]
    {:disposition (code->disposition d)
     :reason (code->reason r)}))

(defn verdict->disposition
  "Map an UnderwritingGovernor verdict to a base disposition before the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
