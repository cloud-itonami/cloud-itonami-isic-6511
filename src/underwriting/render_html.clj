(ns underwriting.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  This namespace does NOT contain a hand-written page. It drives THIS
  repo's real actor stack -- `underwriting.operation` (a langgraph-clj
  StateGraph) -> `underwriting.governor` -> `underwriting.store` --
  through a 13-operation scenario against `underwriting.store/seed-db`,
  and renders whatever actually came out. Every application id, party
  name, coverage amount, policy number, verdict, hold basis and audit
  fact on the page is read back out of the store (or out of the run's
  `:audit` channel) after the run. Nothing is typed in by hand.

  Where the page describes BEHAVIOUR rather than data, it derives that
  too: the rollout-autonomy matrix is computed by actually calling
  `underwriting.phase/gate` for every (phase, op) pair rather than
  transcribing the phase table into prose, and the approval-attribution
  section reports what the store *actually retained* by inspecting the
  committed records. Both self-correct: if someone changes the phase
  table, or fixes the approver drop, the page changes on the next build
  instead of becoming a lie.

  Scenario shape (see `run-demo!`): one full happy-path lifecycle for
  `app-1` (intake -> jurisdiction assessment -> KYC on insured and
  beneficiary -> policy binding), plus four HARD governor holds across
  three distinct rules, one rollout-phase hold, and one human REJECTION
  of an escalated proposal.

  Determinism: no timestamps, no randomness, no wall-clock anywhere in
  the page. Every map iterated for output is sorted on a stable key, so
  two runs against the same seed are byte-identical.

  Usage: `clojure -M:dev:render-html [out-file]`
         (default `docs/samples/operator-console.html`)"
  (:require [jp-go-dds.skin]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [langgraph.graph :as g]
            [underwriting.facts :as facts]
            [underwriting.governor :as governor]
            [underwriting.operation :as op]
            [underwriting.phase :as phase]
            [underwriting.store :as store]))

;; ----------------------------- the scenario -----------------------------

(def ^:private underwriter
  "The licensed underwriter, operating at the widest rollout phase."
  {:actor-id "op-1" :actor-role :underwriter :phase 3})

(def ^:private trainee
  "A second operator pinned to an earlier rollout phase, so the page can
  show the phase gate refusing an op the governor itself was fine with."
  {:actor-id "op-2" :actor-role :underwriter :phase 1})

;; `langgraph.graph/run*` returns the WRAPPER {:state :events :status
;; :frontier} -- the channel values (`:disposition`, `:audit`, ..) live
;; under `:state`, and `:status` is `:done` or `:interrupted`. Reading a
;; channel straight off the wrapper silently yields nil, which would make
;; every escalation look like a non-escalation and quietly skip every
;; approval; both helpers below hand back the wrapper so callers have to
;; pick one deliberately.

(defn- exec! [actor tid ctx request]
  (g/run* actor {:request request :context ctx} {:thread-id tid}))

(defn- resume! [actor tid decision]
  (g/run* actor {:approval decision} {:thread-id tid :resume? true}))

(defn run-demo!
  "Run a fresh `store/seed-db` through the real OperationActor and return
  `{:db db :runs [..]}`.

  `:runs` keeps the final graph state of every operation (including its
  `:audit` channel) so the renderer can show the advisor's own proposal
  traces and the approval-granted facts -- neither of which the store's
  persisted ledger keeps.

  The approval is only submitted when the operation ACTUALLY escalated.
  That matters: if a rule changes so that some op stops escalating, this
  driver quietly stops approving it rather than crashing or, worse,
  silently rendering a stale claim.

  Steps, and what each one is here to prove:

    t01  premature `:policy/bind app-1`  -- HARD hold. Binding coverage
         before the jurisdiction assessment exists trips two rules at
         once (no cited spec-basis, and the required documents are not
         satisfied).
    t02  `:application/intake app-1`     -- the ONE auto-commit cell in
         the whole phase table (phase 3 x intake, governor clean).
    t03  `:application/intake app-2` at phase 1 -- same op, earlier
         phase: enabled to write but not auto, so a human approves.
    t04  `:jurisdiction/assess app-1` at phase 1 -- the governor is
         clean here; the ROLLOUT PHASE is what refuses. Distinct from a
         governor hold, and rendered as such.
    t05  `:jurisdiction/assess app-1`    -- escalates, approved, commits.
    t06  `:kyc/screen party-1`           -- insured, clean. Approved.
    t07  `:kyc/screen party-2`           -- beneficiary, clean. Approved.
    t08  `:kyc/screen party-5`           -- clean on every local field.
    t09  `:kyc/screen party-3`           -- HARD hold: sanctions/PEP hit.
         Never reaches a human at all.
    t10  `:kyc/screen party-4`           -- no identification document,
         so low confidence -> escalate. The human REJECTS. Proves the
         approval seam is a real decision point, not a rubber stamp.
    t11  `:jurisdiction/assess app-2`    -- HARD hold: no official
         spec-basis on file for that jurisdiction. Never invent one.
    t12  `:policy/bind app-1`            -- clean, but `:stake :actuation`
         so it escalates at every phase. Approved -> coverage bound.
    t13  `:policy/bind app-2`            -- HARD hold, same two rules as
         t01, on an application that can never reach a clean assessment."
  []
  (let [db    (store/seed-db)
        actor (op/build db)
        runs  (atom [])
        ;; The approval is submitted only when the graph ACTUALLY paused
        ;; at the interrupt-before gate. If a rule ever changes so that an
        ;; op stops escalating, this driver stops approving it instead of
        ;; throwing on a dead thread -- and the page then shows the new
        ;; behaviour rather than a stale claim about the old one.
        go    (fn [label tid ctx request approval]
                (let [paused (exec! actor tid ctx request)
                      final  (if (and approval (= :interrupted (:status paused)))
                               (resume! actor tid approval)
                               paused)]
                  (swap! runs conj {:label    label
                                    :thread   tid
                                    :operator (:actor-id ctx)
                                    :phase    (:phase ctx)
                                    :request  request
                                    :approval approval
                                    :status   (:status final)
                                    :state    (:state final)})
                  final))
        approve  {:status :approved :by "underwriter-1"}
        approve2 {:status :approved :by "reviewer-2"}
        reject   {:status :rejected :by "underwriter-1"}]

    (go "policy binding attempted before any assessment exists"
        "t01" underwriter {:op :policy/bind :subject "app-1"} nil)

    (go "application intake, governor clean at the widest phase"
        "t02" underwriter {:op :application/intake :subject "app-1"
                           :patch {:id "app-1" :status :ready}} nil)

    (go "same intake op one rollout phase earlier -- write enabled, autonomy not"
        "t03" trainee {:op :application/intake :subject "app-2"
                       :patch {:id "app-2" :status :ready}} approve2)

    (go "jurisdiction assessment refused by the rollout phase, not the governor"
        "t04" trainee {:op :jurisdiction/assess :subject "app-1"} approve)

    (go "jurisdiction assessment against an official spec-basis"
        "t05" underwriter {:op :jurisdiction/assess :subject "app-1"} approve)

    (go "KYC screening -- insured"
        "t06" underwriter {:op :kyc/screen :subject "party-1"} approve)

    (go "KYC screening -- beneficiary"
        "t07" underwriter {:op :kyc/screen :subject "party-2"} approve)

    (go "KYC screening -- clean on every local field"
        "t08" underwriter {:op :kyc/screen :subject "party-5"} approve)

    (go "KYC screening -- sanctions/PEP hit"
        "t09" underwriter {:op :kyc/screen :subject "party-3"} approve)

    (go "KYC screening -- no identification document, and the human refuses"
        "t10" underwriter {:op :kyc/screen :subject "party-4"} reject)

    (go "jurisdiction assessment with no official spec-basis on file"
        "t11" underwriter {:op :jurisdiction/assess :subject "app-2"} approve)

    (go "policy binding -- clean, high-stakes, human-approved"
        "t12" underwriter {:op :policy/bind :subject "app-1"} approve)

    (go "policy binding on an application that has no assessment"
        "t13" underwriter {:op :policy/bind :subject "app-2"} approve)

    {:db db :runs @runs}))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- nm [k] (if (keyword? k) (name k) (str k)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- span [cls v] (str "<span class=\"" cls "\">" v "</span>"))

(defn- dash [] (span "muted" "&mdash;"))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" (esc %) "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (if (seq rows) (str (str/join "\n" rows) "\n") "")
       "      </tbody>\n"
       "    </table>\n"))

(defn- section [title lede body]
  (str "  <section class=\"card\">\n"
       "    <h2>" (esc title) "</h2>\n"
       "    <p class=\"muted\">" lede "</p>\n"
       body
       "  </section>\n"))

;; ----------------------------- derivations -----------------------------
;;
;; Everything below reads the post-run store / audit channels. No page
;; content is asserted from this file's own knowledge of the domain.

(defn- audit-facts
  "Every audit fact produced by the whole scenario, in run order."
  [runs]
  (vec (mapcat #(:audit (:state %)) runs)))

(defn- hold? [f] (contains? #{:governor-hold :approval-rejected} (:t f)))

(defn- hard-hold?
  "A HARD governor hold: the governor itself named at least one rule a
  human is not allowed to override. A rollout-phase refusal also arrives
  as `:governor-hold`, but with an empty `:basis` -- counting those as
  HARD would overstate the governor."
  [f]
  (and (= :governor-hold (:t f)) (seq (:basis f))))

(defn- hold-kind [f]
  (cond (hard-hold? f)                      :hard
        (= :approval-rejected (:t f))       :human
        :else                               :phase))

(defn- hold-entries
  "One entry per (hold, rule) pair, so a hold that tripped two rules is
  attributed to both."
  [ledger]
  (vec (for [h ledger
             :when (hold? h)
             r (or (seq (:basis h)) [(or (:phase-reason h) :unclassified)])]
         {:rule r :kind (hold-kind h) :hold h})))

(defn- party-ids
  "Party ids DERIVED from the store: everyone referenced by a seeded
  application, plus everyone this run actually addressed. No hand-typed
  roster -- adding a party to `store/demo-data` shows up here for free."
  [db ledger]
  (->> (concat (mapcat (fn [a] (cons (:insured a) (:beneficiaries a)))
                       (store/all-applications db))
               (map :subject ledger))
       (filter some?)
       distinct
       (filter #(store/party db %))
       sort
       vec))

(defn- last-fact-for [ledger subject]
  (last (filter #(= subject (:subject %)) ledger)))

(defn- status-cell [ledger subject]
  (let [f (last-fact-for ledger subject)]
    (cond
      (nil? f) (span "muted" "no activity")
      (= :committed (:t f)) (span "ok" "committed")
      (hard-hold? f) (span "critical"
                           (str "HARD hold &middot; "
                                (esc (str/join ", " (map nm (:basis f))))))
      (= :approval-rejected (:t f)) (span "warn" "approval refused")
      (= :governor-hold (:t f)) (span "warn"
                                      (str "phase hold &middot; "
                                           (esc (nm (or (:phase-reason f) :unknown)))))
      :else (span "muted" "in progress"))))

;; --- applications -------------------------------------------------------

(defn- application-rows [db ledger]
  (for [{:keys [id insured beneficiaries coverage-amount currency
                jurisdiction status policy-number]} (store/all-applications db)]
    (row (code id)
         (str (esc (:name (store/party db insured))) " " (span "muted" (esc (str "(" insured ")"))))
         (esc (str/join ", " (map #(str (:name (store/party db %)) " (" % ")") beneficiaries)))
         (str (span "amt" (esc (format "%,d" (long coverage-amount)))) " " (esc currency))
         (str (esc jurisdiction) " "
              (if (facts/spec-basis jurisdiction)
                (span "ok" "spec-basis on file")
                (span "critical" "no spec-basis")))
         (esc (nm status))
         (if policy-number (span "ok" (code policy-number)) (dash))
         (status-cell ledger id))))

;; --- parties ------------------------------------------------------------

(defn- kyc-cell [k]
  (if (nil? k)
    (span "muted" "not committed")
    (case (:verdict k)
      :clear      (span "ok" "clear")
      :hit        (span "critical" "hit")
      :incomplete (span "warn" "incomplete")
      (span "muted" (esc (nm (:verdict k)))))))

(defn- party-rows [db ledger]
  (for [pid (party-ids db ledger)
        :let [p (store/party db pid)]]
    (row (code pid)
         (esc (:name p))
         (esc (nm (:role p)))
         (if (:sanctions-hit? p) (span "critical" "yes") (span "ok" "no"))
         (if (:id-doc p) (str (span "ok" "on file ") (code (:id-doc p))) (span "warn" "missing"))
         (kyc-cell (store/kyc-of db pid))
         (status-cell ledger pid))))

;; --- rollout autonomy matrix -------------------------------------------

(defn- all-ops
  "Sorted so the table is stable; taken from the phase namespace's own
  sets, so a newly declared op appears here without editing this file."
  []
  (vec (sort-by str (into phase/read-ops phase/write-ops))))

(defn- gate-cell
  "Ask the REAL phase gate what it does to a governor-clean verdict for
  this (phase, op). Not a transcription of the phase table -- a call into
  the same code path `underwriting.operation/:decide` uses."
  [p o]
  (let [{:keys [disposition reason]} (phase/gate p {:op o} :commit)]
    (case disposition
      :commit   (span "ok" "auto-commit")
      :escalate (str (span "warn" "human approval")
                     (when reason (str " " (span "muted" (esc (str "(" (nm reason) ")"))))))
      (str (span "critical" "blocked")
           (when reason (str " " (span "muted" (esc (str "(" (nm reason) ")")))))))))

(defn- gate-rows []
  (let [phases (sort (keys phase/phases))]
    (for [o (all-ops)]
      (apply row (code o)
             (if (contains? phase/read-ops o) "read" "write")
             (map #(gate-cell % o) phases)))))

(defn- gate-headers []
  (into ["Op" "Kind"]
        (for [p (sort (keys phase/phases))]
          (str "Phase " p " — " (:label (get phase/phases p))))))

;; --- governor holds observed -------------------------------------------

(defn- kind-badge [k]
  (case k
    :hard  (span "critical" "HARD &middot; not overridable")
    :human (span "warn" "human refusal")
    (span "warn" "rollout phase")))

(defn- rule-detail
  "The governor's own human-readable detail for this rule, pulled off the
  first hold that carried it."
  [entries]
  (or (->> entries
           (mapcat #(:violations (:hold %)))
           (filter #(= (:rule (first entries)) (:rule %)))
           (map :detail)
           (remove nil?)
           first)
      ""))

(defn- hold-rule-rows [ledger]
  (let [by-rule (group-by :rule (hold-entries ledger))]
    (for [r (sort-by str (keys by-rule))
          :let [entries (get by-rule r)]]
      (row (code r)
           (kind-badge (:kind (first entries)))
           (span "num" (str (count entries)))
           (esc (str/join ", " (distinct (map #(nm (:op (:hold %))) entries))))
           (esc (str/join ", " (distinct (map #(:subject (:hold %)) entries))))
           (esc (rule-detail entries))))))

;; --- advisor proposal traces -------------------------------------------

(defn- proposal-rows [runs]
  (for [f (audit-facts runs)
        :when (= :underwriterllm-proposal (:t f))]
    (row (code (nm (:op f)))
         (code (:subject f))
         (esc (:summary f))
         (esc (:rationale f))
         (if (seq (:cites f))
           (esc (str/join ", " (map nm (:cites f))))
           (span "critical" "none &mdash; nothing cited"))
         (let [c (:confidence f)]
           (span (if (< (double c) governor/confidence-floor) "warn" "num")
                 (esc (format "%.2f" (double c))))))))

;; --- approval attribution (MEASURED, not assumed) -----------------------

(defn- retained-approver
  "Read the COMMITTED record back out of the store and report whether the
  approver identity survived the commit. This is a measurement, not a
  claim: `underwriting.operation` attaches `:approved-by` to the record's
  `:payload`, and each `store/commit-record!` effect branch decides for
  itself whether it reads `:payload` or `:value` -- so retention differs
  per op in this repo. Rendering it this way means the page corrects
  itself if the store is ever changed."
  [db {:keys [op subject]}]
  (case op
    :application/intake
    (let [m (store/application db subject)]
      {:record "application record" :found? (contains? m :approved-by) :value (:approved-by m)})

    :jurisdiction/assess
    (let [m (store/assessment-of db subject)]
      {:record "assessment record" :found? (contains? m :approved-by) :value (:approved-by m)})

    :kyc/screen
    (let [m (store/kyc-of db subject)]
      {:record "KYC record" :found? (contains? m :approved-by) :value (:approved-by m)})

    :policy/bind
    (let [pn (:policy-number (store/application db subject))
          m  (first (filter #(= pn (get % "record_id")) (store/binding-history db)))]
      {:record "policy-binding record"
       :found? (boolean (some #(contains? m %) ["approved_by" "approved-by"]))
       :value  (or (get m "approved_by") (get m "approved-by"))})

    {:record "unknown" :found? false :value nil}))

(defn- approval-rows [db runs]
  (for [r runs
        f (:audit (:state r))
        :when (= :approval-granted (:t f))
        :let [ret (retained-approver db f)]]
    (row (code (nm (:op f)))
         (code (:subject f))
         (esc (:by f))
         (esc (:record ret))
         (if (:found? ret)
           (span "ok" (str "retained &middot; " (code (:value ret))))
           (span "critical" (str (esc (:by f)) " &mdash; audit fact only, not retained in the record"))))))

(defn- approval-summary [db runs]
  (let [rets (for [r runs
                   f (:audit (:state r))
                   :when (= :approval-granted (:t f))]
               (retained-approver db f))
        kept (count (filter :found? rets))
        lost (- (count rets) kept)]
    (str "Measured on this run by reading each committed record back out of the store: "
         (span "num" (str (count rets))) " approval"
         (when (not= 1 (count rets)) "s") " granted, "
         (span "ok" (str kept " retained")) " the approver in the committed record, "
         (if (pos? lost)
           (span "critical" (str lost " did not"))
           (span "ok" "0 did not"))
         ". Where the approver was dropped the row still names them, taken from the audit fact, "
         "and says so explicitly &mdash; an empty column would be indistinguishable from "
         "&ldquo;nobody approved this&rdquo;.")))

;; --- jurisdiction spec-basis catalog ------------------------------------

(defn- catalog-rows []
  (for [iso3 (sort (keys facts/catalog))
        :let [{:keys [name owner-authority legal-basis provenance required-docs]}
              (get facts/catalog iso3)]]
    (row (code iso3)
         (esc name)
         (esc owner-authority)
         (esc legal-basis)
         (str "<a href=\"" (esc provenance) "\">" (esc provenance) "</a>")
         (str "<ul>" (str/join (map #(str "<li>" (esc %) "</li>") required-docs)) "</ul>"))))

;; --- policy binding records --------------------------------------------

(defn- binding-rows [db]
  (for [b (store/binding-history db)]
    (row (code (get b "record_id"))
         (esc (get b "kind"))
         (esc (get b "insured"))
         (esc (str/join ", " (get b "beneficiaries")))
         (span "amt" (esc (format "%,d" (long (get b "coverage_amount")))))
         (esc (get b "jurisdiction"))
         (if (get b "immutable") (span "ok" "append-only") (dash)))))

;; --- persisted audit ledger --------------------------------------------

(defn- ledger-rows [ledger]
  (map-indexed
   (fn [i {:keys [t op subject disposition basis phase-reason phase confidence] :as f}]
     (row (span "num" (str (inc i)))
          (cond (hard-hold? f) (span "critical" (esc (nm t)))
                (hold? f)      (span "warn" (esc (nm t)))
                :else          (span "ok" (esc (nm t))))
          (code (nm (or op :n-a)))
          (code subject)
          (esc (nm (or disposition :n-a)))
          (cond (seq basis)  (esc (str/join ", " (map nm basis)))
                phase-reason (str (esc (nm phase-reason))
                                  " " (span "muted" (esc (str "(phase " phase ")"))))
                :else        (dash))
          (if confidence (span "num" (esc (format "%.2f" (double confidence)))) (dash))))
   ledger))

;; ----------------------------- the page -----------------------------

(defn render
  "Render the whole console from a store that has already been run
  through `run-demo!` (plus that run's per-operation graph states)."
  [db runs]
  (let [ledger (vec (store/ledger db))
        hard   (filterv hard-hold? ledger)
        rules  (vec (sort-by str (distinct (mapcat :basis hard))))
        cov    (facts/coverage)]
    (str
     "<!doctype html>\n"
     "<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     "<title>cloud-itonami-isic-6511 &middot; Life insurance &mdash; Operator Console</title>\n"
     "<style>\n" (jp-go-dds.skin/dds+skin) "\n</style>\n"
     "</head><body>\n"

     "<header class=\"bar\">\n"
     "  <h1>Life insurance (ISIC Rev.5 6511) &mdash; Operator Console</h1>\n"
     "  <span class=\"badge\">build-time sample &middot; generated by running the real actor &middot; "
     (esc (str (count runs) " operations, " (count ledger) " persisted audit facts, "
               (count hard) " HARD governor holds across " (count rules) " rules"))
     "</span>\n"
     "</header>\n"

     "<main class=\"container\">\n"

     "  <section class=\"banner\">\n"
     "    <p>Every value on this page was produced by executing "
     (code "underwriting.operation") " (a langgraph-clj StateGraph) against "
     (code "underwriting.store/seed-db") " and reading the result back out. "
     "Regenerate with " (code "clojure -M:dev:render-html") ". The build "
     "<strong>refuses to write this file</strong> if the scenario produced no HARD governor "
     "hold, so the page cannot quietly degrade into a demo where the governor never says no.</p>\n"
     "  </section>\n"

     (section "Applications"
              (str "The seeded application directory, with whatever status this run left it in. "
                   "Jurisdiction is annotated with whether "
                   (code "underwriting.facts") " actually holds an official spec-basis for it "
                   "&mdash; a jurisdiction with none can never reach a policy binding.")
              (table ["Application" "Insured" "Beneficiaries" "Coverage" "Jurisdiction"
                      "Status" "Policy number" "Last decision"]
                     (application-rows db ledger)))

     (section "Parties"
              (str "Everyone referenced by a seeded application or addressed by this run. "
                   "The KYC column is the "
                   (code "committed") " screening verdict in the store &mdash; a verdict the "
                   "governor HARD-held never gets committed at all, which is why a sanctions "
                   "hit shows as uncommitted here rather than as a stored clearance.")
              (table ["Party" "Name" "Role" "Sanctions/PEP flag" "Identification"
                      "Committed KYC verdict" "Last decision"]
                     (party-rows db ledger)))

     (section "Rollout autonomy matrix"
              (str "Computed at build time by calling " (code "underwriting.phase/gate")
                   " for every (phase, op) pair with a <em>governor-clean</em> verdict, "
                   "so this table is the gate itself rather than a description of it. "
                   "Default phase is " (code (str phase/default-phase)) ". "
                   "A cell only ever answers &ldquo;how much autonomy does the phase grant&rdquo;; "
                   "the governor still runs first and can override any of them down to a hold.")
              (table (gate-headers) (gate-rows)))

     (section "Holds observed in this run"
              (str "Grouped by the rule that fired, with the governor's own detail text. "
                   (span "critical" "HARD") " holds come from "
                   (code "underwriting.governor") " and a human approver cannot override them. "
                   "The other kinds are shown separately on purpose: a rollout-phase refusal and a "
                   "human refusal are not the compliance layer speaking, and collapsing them "
                   "together would overstate the governor.")
              (table ["Rule" "Kind" "Times fired" "Ops" "Subjects" "Governor detail"]
                     (hold-rule-rows ledger)))

     (section "Advisor proposals (the contained intelligence node)"
              (str "The Underwriter-LLM's own output for each operation, straight off the run's "
                   (code ":audit") " channel. It only ever proposes; every row here was then "
                   "censored by the governor before anything could touch the store. "
                   "Confidence below the floor of "
                   (span "num" (esc (format "%.2f" (double governor/confidence-floor))))
                   " forces a human look. An empty citation list is a HARD violation, not a "
                   "style problem.")
              (table ["Op" "Subject" "Draft" "Rationale" "Cites" "Confidence"]
                     (proposal-rows runs)))

     (section "Approval attribution"
              (approval-summary db runs)
              (table ["Op" "Subject" "Approver (audit fact)" "Committed record"
                      "Approver in the committed record?"]
                     (approval-rows db runs)))

     (section "Jurisdiction spec-basis catalog"
              (str "The official sources the governor checks every jurisdiction proposal against. "
                   (esc (:note cov)) " Covered: "
                   (span "num" (str (:covered cov))) " of "
                   (span "num" (str (:requested cov))) ".")
              (table ["ISO3" "Jurisdiction" "Authority" "Legal basis" "Provenance"
                      "Required underwriting documents"]
                     (catalog-rows)))

     (section "Policy binding records"
              (str "Append-only binding drafts produced by "
                   (code "underwriting.registry") ". These are the record an operator keeps; "
                   "the certificate this actor emits is deliberately unsigned, because signing "
                   "is the licensed underwriter's act and not the actor's.")
              (table ["Policy number" "Kind" "Insured" "Beneficiaries" "Coverage"
                      "Jurisdiction" "History"]
                     (binding-rows db)))

     (section "Persisted audit ledger"
              (str "The append-only decision log the store actually kept &mdash; one fact per "
                   "operation, commit or hold. This is the evidence trail if a binding is later "
                   "disputed.")
              (table ["#" "Fact" "Op" "Subject" "Disposition" "Basis" "Confidence"]
                     (ledger-rows ledger)))

     "</main>\n"
     "<footer class=\"container\">\n"
     "  <p>Generated by " (code "underwriting.render-html")
     " from " (code "underwriting.store/seed-db")
     ". Deterministic: no timestamps, no randomness &mdash; two builds of the same commit are "
     "byte-identical.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

;; ----------------------------- entry point -----------------------------

(defn -main
  "Run the scenario, assert the invariants that keep this page honest, and
  write the console.

  The invariants are build-time failures rather than conventions, because
  a console rendered from a run in which the governor never held anything
  would show a compliance layer that looks like a rubber stamp -- and
  would do it silently."
  [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs]} (run-demo!)
        ledger (vec (store/ledger db))
        hard   (filterv hard-hold? ledger)
        rules  (into (sorted-set) (mapcat :basis hard))
        apps   (store/all-applications db)]

    (when (zero? (count hard))
      (throw (ex-info (str "no HARD governor hold in this scenario -- the console would "
                           "misrepresent the UnderwritingGovernor as a rubber stamp")
                      {:ledger-facts (count ledger) :operations (count runs)})))

    (when (< (count rules) 3)
      (throw (ex-info (str "fewer than 3 distinct HARD governor rules exercised -- one hold "
                           "is not evidence that the governor has more than one reason to say no")
                      {:rules (vec rules)})))

    (when (empty? apps)
      (throw (ex-info "no applications in the store -- nothing to render" {})))

    (when (empty? (store/binding-history db))
      (throw (ex-info (str "no policy binding was produced -- the happy path never completed, "
                           "so the console would only show refusals")
                      {})))

    ;; A thread that is still :interrupted after we handed it an approval
    ;; means the resume never landed. That failure is silent -- the
    ;; operation simply produces no ledger fact -- so it is checked here
    ;; rather than left to be noticed by eye. (It is not hypothetical:
    ;; reading `:disposition` off the run* wrapper instead of off
    ;; `:state` skipped every approval in this scenario's first build.)
    (let [stranded (filterv #(and (:approval %) (= :interrupted (:status %))) runs)]
      (when (seq stranded)
        (throw (ex-info (str "approval was submitted but " (count stranded)
                             " operation(s) are still paused at the interrupt gate")
                        {:threads (mapv :thread stranded)}))))

    (let [f (io/file out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render db runs) :encoding "UTF-8")
      (println (str "wrote " out
                    " (" (count runs) " operations, "
                    (count ledger) " persisted audit facts, "
                    (count hard) " HARD governor holds across "
                    (count rules) " distinct rules " (vec rules) ", "
                    (count apps) " applications, "
                    (count (store/binding-history db)) " policy binding record(s), "
                    (.length f) " bytes)"))))
  nil)
