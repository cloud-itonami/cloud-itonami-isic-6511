(ns underwriting.governor
  "UnderwritingGovernor -- the independent compliance layer that earns the
  Underwriter-LLM the right to commit. The LLM has no notion of
  jurisdiction disclosure law, AML/sanctions exposure or when an act stops
  being a draft and becomes a real-world policy binding, so this MUST be
  a separate system able to *reject* a proposal and fall back to HOLD --
  the life-insurance analog of `cloud-itonami-M6910`'s RegistrarGovernor,
  `cloud-itonami-L6810`'s RealtorGovernor, robotaxi's Minimal Risk
  Condition and gftd-talent-actor's PolicyGovernor.

  Five checks, in priority order. The first three are HARD violations: a
  human approver CANNOT override them (you don't get to approve your way
  past an AML/sanctions hit or a fabricated underwriting requirement).
  The last two are SOFT: they ask a human to look (low confidence /
  actuation), and the human may approve -- but see `underwriting.phase`:
  for `:stake :actuation` (a real policy binding -- issuing real coverage)
  NO phase ever allows auto-commit either. Two independent layers agree
  that actuation is always a human call.

    1. Spec-basis        -- did the jurisdiction proposal cite an OFFICIAL
                             source (`underwriting.facts`), or invent one?
    2. Sanctions hold     -- does any party on the application carry a
                             sanctions/PEP hit (screened or on file)?
    3. Document complete  -- for a bind proposal, are the jurisdiction's
                             required underwriting docs actually satisfied?
    4. Confidence floor   -- LLM confidence below threshold -> escalate.
    5. Actuation gate     -- :stake :actuation -> always escalate; never
                             auto, at any phase (structural, not a policy
                             toggle)."
  (:require [underwriting.facts :as facts]
            [underwriting.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  :actuation = a real policy binding (issuing real life-insurance
  coverage). There is exactly one member on purpose: actuation is not a
  spectrum."
  #{:actuation})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:jurisdiction/assess` (or `:policy/bind`) proposal with no
  spec-basis citation is a HARD violation -- never invent a
  jurisdiction's underwriting law."
  [{:keys [op]} proposal]
  (when (contains? #{:jurisdiction/assess :policy/bind} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- sanctions-violations
  "A sanctions/PEP hit on any party involved -- screened in THIS proposal
  or already on file in the store -- is a HARD, un-overridable hold."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (= :hit (get-in proposal [:value :verdict]))
        party-ids (when (= op :policy/bind)
                    (cons (:insured (store/application st subject))
                          (:beneficiaries (store/application st subject))))
        hit-on-file? (some #(= :hit (:verdict (store/kyc-of st %))) party-ids)]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :sanctions-hit
        :detail "制裁/PEPリスト一致のある関係者を含む申込は進められない"}])))

(defn- document-violations
  "For `:policy/bind`, the jurisdiction's required underwriting docs must
  actually be satisfied -- do not trust the advisor's self-reported
  confidence alone."
  [{:keys [op subject]} st]
  (when (= op :policy/bind)
    (let [a (store/application st subject)
          assessment (store/assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-docs-satisfied?
                      (:jurisdiction a) (:checklist assessment)))
        [{:rule :incomplete-documents
          :detail "法域の必要書類が充足していない状態での成立提案"}]))))

(defn check
  "Censors an Underwriter-LLM proposal against the governor rules. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes? bool
    :hard? bool}.

   - :hard?       -- at least one HARD violation. Forces HOLD; a human
                    cannot override.
   - :escalate?   -- soft: low confidence OR actuation. A human decides.
   - :ok?         -- clean AND not escalating: safe to auto-commit."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (sanctions-violations request proposal st)
                           (document-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
