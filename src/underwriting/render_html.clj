(ns underwriting.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this blueprint: the repo had a
  product LP (`docs/index.html`) and an operator guide, but no demo page
  showing the actor actually deciding anything. This namespace drives the
  REAL actor stack -- `underwriting.operation` (langgraph StateGraph) ->
  `underwriting.governor` (UnderwritingGovernor) -> `underwriting.phase`
  (rollout gate) -> `underwriting.store` (SSoT + append-only ledger) --
  and renders the page from what actually came back.

  Nothing on the page is hand-typed telemetry:

    - applications / parties come from `underwriting.store/demo-data`
      read back through the `Store` protocol AFTER the run;
    - the jurisdiction table is `underwriting.facts/catalog` and
      `underwriting.facts/coverage`;
    - the phase table is `underwriting.phase/phases` (labels, write sets
      and auto sets are read out of the map, not restated in prose);
    - every disposition, violation rule, confidence and approval reason
      is read out of the graph state / governor verdict / store ledger;
    - the approver-attribution disclosure is MEASURED at render time by
      looking for an approver key in the committed register, so it stays
      true if the store is later changed (see `approver-attribution`).

  `-main` refuses to write a page whose run produced no governor HOLD, or
  which failed to exercise every HARD rule the governor implements -- a
  console that shows only happy paths would be advertising, not evidence.

  Deterministic: no timestamps, no random ids, no wall clock. Two runs
  produce byte-identical output; `-main` is safe to re-run in CI.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [underwriting.corporate-intel :as corporate-intel]
            [underwriting.facts :as facts]
            [underwriting.governor :as governor]
            [underwriting.operation :as op]
            [underwriting.phase :as phase]
            [underwriting.store :as store]
            [underwriting.underwriterllm :as underwriterllm]))

(def ^:private underwriter
  "The human operator in this scenario -- a licensed underwriter. Only ever
  supplies an approval decision; never bypasses the governor."
  {:actor-id "op-1" :actor-role :underwriter})

(def ^:private approver-id
  "The approving underwriter's operator id, carried on the approval message
  as `:by`. Whether it survives into the SSoT is measured, not assumed."
  "uw-lic-001")

(def ^:private hard-rules
  "Every HARD (un-overridable) rule `underwriting.governor` can raise. The
  demo must exercise all of them -- see `-main`."
  #{:no-spec-basis :sanctions-hit :incomplete-documents})

;; ----------------------------- the real run -----------------------------

(defn- exec! [actor tid ctx request]
  (g/run* actor {:request request :context ctx} {:thread-id tid}))

(defn- resume! [actor tid status]
  (g/run* actor {:approval {:status status :by approver-id}}
          {:thread-id tid :resume? true}))

(defn- observe
  "Runs one operation and, when the actor pauses for a human, resumes it
  with `approval` (:approved | :rejected | nil = leave it pending).
  Returns the observation map -- every field is read back out of the graph
  state, the governor verdict or the audit channel."
  [actor phase-n acc {:keys [thread note request approval]}]
  (let [ctx  (assoc underwriter :phase phase-n)
        res  (exec! actor thread ctx request)
        paused? (= :interrupted (:status res))
        fin  (if (and paused? approval) (resume! actor thread approval) res)
        st   (:state fin)
        audit (:audit st)
        fact-of (fn [t] (last (filter #(= t (:t %)) audit)))]
    (swap! acc conj
           {:thread      thread
            :note        note
            :op          (:op request)
            :subject     (:subject request)
            :phase       phase-n
            :status      (:status fin)
            :disposition (:disposition st)
            :confidence  (get-in st [:verdict :confidence])
            :hard?       (get-in st [:verdict :hard?])
            :violations  (get-in st [:verdict :violations])
            :escalated?  paused?
            :approval    (when paused? approval)
            :ask-reason  (:reason (fact-of :approval-requested))
            :phase-reason (:phase-reason (fact-of :governor-hold))
            :approver    (:by (fact-of :approval-granted))
            :audit       audit})
    fin))

(defn run-demo!
  "Drives a fresh seeded store through a scenario that reaches every
  disposition this actor can produce, using ONLY the ids in
  `underwriting.store/demo-data` and the jurisdictions in
  `underwriting.facts/catalog`:

    - app-1 (JPN, registered spec-basis, clean parties) walks the whole
      lifecycle: intake under phase 2 (human approval -- intake is a
      `:writes` op but not `:auto` at that phase), intake again under
      phase 3 (auto-commit, governor clean, no human), the JPN
      underwriting-document assessment, KYC on party-1 and party-2, and
      finally `:policy/bind`, which ALWAYS escalates (`:stake :actuation`)
      and is approved by a licensed underwriter -> policy JPN-00000000;
    - party-3 carries a sanctions/PEP hit  -> HARD `:sanctions-hit`;
    - app-2's jurisdiction (\"ATL\") is not in `underwriting.facts`, so
      the advisor cites nothing -> HARD `:no-spec-basis`;
    - binding app-2 anyway raises TWO hard rules at once -- no spec-basis
      AND `:incomplete-documents` (its jurisdiction's required docs were
      never satisfied, because there is no requirement catalog for it);
    - party-4 has no identity document -> confidence 0.4, below
      `underwriting.governor/confidence-floor` -> the human is asked and
      REJECTS, which is a hold too (`:approver-rejected`);
    - party-5 is clean on every LOCAL field, and is only caught by the
      optional `underwriting.corporate-intel` cross-reference into
      cloud-itonami-isic-8291 -- whose own DisclosureGovernor escalates to
      ITS reviewer, so this side reports inconclusive and refuses to clear;
    - intake under phase 0 (read-only) is held by the phase gate even
      though the governor itself was clean.

  Returns `{:db <store> :runs [observation ..]}`."
  []
  (let [db      (store/seed-db)
        actor   (op/build db)
        ;; the optional cross-reference: one 8291 actor, built once, and
        ;; consulted through 8291's OWN governed op -- no bypass.
        intel   (corporate-intel/build)
        xref    (fn [nm] (corporate-intel/screen nm {:actor intel}))
        actor+x (op/build db {:advisor (underwriterllm/mock-advisor
                                        {:corporate-intel-screen xref})})
        acc     (atom [])
        step!   (fn [phase-n m] (observe actor phase-n acc m))]

    (step! 0 {:thread "app-1-intake-phase-0"
              :note "phase 0 is read-only: the write never reaches the SSoT"
              :request {:op :application/intake :subject "app-1"
                        :patch {:id "app-1" :status :ready}}})

    (step! 2 {:thread "app-1-intake-phase-2"
              :note "phase 2 allows the write but not autonomously"
              :request {:op :application/intake :subject "app-1"
                        :patch {:id "app-1" :status :ready}}
              :approval :approved})

    (step! 3 {:thread "app-1-intake-phase-3"
              :note "phase 3 + governor clean + high confidence = auto-commit"
              :request {:op :application/intake :subject "app-1"
                        :patch {:id "app-1" :status :ready}}})

    (step! 3 {:thread "app-1-assess"
              :note "JPN has an official spec-basis; a human still signs it off"
              :request {:op :jurisdiction/assess :subject "app-1"}
              :approval :approved})

    (step! 3 {:thread "party-1-kyc"
              :note "insured: identity document on file, no list match"
              :request {:op :kyc/screen :subject "party-1"}
              :approval :approved})

    (step! 3 {:thread "party-2-kyc"
              :note "beneficiary: screened on the same footing as the insured"
              :request {:op :kyc/screen :subject "party-2"}
              :approval :approved})

    (step! 3 {:thread "app-1-bind"
              :note "actuation: real coverage; never auto at ANY phase"
              :request {:op :policy/bind :subject "app-1"}
              :approval :approved})

    (step! 3 {:thread "party-3-kyc"
              :note "sanctions/PEP match -- no human is offered the choice"
              :request {:op :kyc/screen :subject "party-3"}})

    (step! 3 {:thread "app-2-assess"
              :note "unregistered jurisdiction: requirements must not be invented"
              :request {:op :jurisdiction/assess :subject "app-2"}})

    (step! 3 {:thread "app-2-bind"
              :note "binding on an unassessed application: two hard rules at once"
              :request {:op :policy/bind :subject "app-2"}})

    (step! 3 {:thread "party-4-kyc"
              :note "no identity document -> below the confidence floor; human declines"
              :request {:op :kyc/screen :subject "party-4"}
              :approval :rejected})

    (observe actor+x 3 acc
             {:thread "party-5-kyc-corporate-intel"
              :note "clean locally; isic-8291 escalated to its own reviewer, so this side cannot clear it"
              :request {:op :kyc/screen :subject "party-5"}})

    {:db db :runs @acc}))

;; ----------------------------- measurement -----------------------------

(defn- ledger-holds [db]
  (filter #(#{:governor-hold :approval-rejected} (:t %)) (store/ledger db)))

(defn- observed-hard-rules
  "The HARD rules this run actually made the governor raise, read out of
  the committed ledger (not out of the scenario's intentions)."
  [db]
  (into (sorted-set)
        (comp (mapcat :basis) (filter hard-rules))
        (ledger-holds db)))

(defn- approver-of
  "The approving underwriter the graph audit recorded for `op`/`subject` in
  this run, or nil if nobody was ever asked."
  [runs op subject]
  (->> runs
       (filter #(and (= op (:op %)) (= subject (:subject %))))
       (keep :approver)
       first))

(defn approver-attribution
  "MEASURED, not asserted: for each committed effect, is the approving
  underwriter still readable from the SSoT afterwards?

  `underwriting.operation` puts `:approved-by` on the record's `:payload`
  only. Whether it survives depends on which key that effect's branch of
  `underwriting.store/commit-record!` reads -- so this function does not
  claim anything about the store, it looks in the register the store hands
  back and reports what is there. If the store is changed, this table
  changes with it instead of becoming a stale accusation."
  [db runs]
  (let [approver-key (fn [m]
                       (cond (nil? m) nil
                             (contains? m :approved-by) (:approved-by m)
                             (contains? m "approved_by") (get m "approved_by")))]
    (for [{:keys [effect register op subject reader]}
          [{:effect :application/upsert :op :application/intake :subject "app-1"
            :reader "store/application \"app-1\""
            :register (store/application db "app-1")}
           {:effect :assessment/set :op :jurisdiction/assess :subject "app-1"
            :reader "store/assessment-of \"app-1\""
            :register (store/assessment-of db "app-1")}
           {:effect :kyc/set :op :kyc/screen :subject "party-1"
            :reader "store/kyc-of \"party-1\""
            :register (store/kyc-of db "party-1")}
           {:effect :policy/mark-bound :op :policy/bind :subject "app-1"
            :reader "first (store/binding-history)"
            :register (first (store/binding-history db))}]]
      (let [retained (approver-key register)
            audited  (approver-of runs op subject)]
        {:effect effect
         :reader reader
         :retained retained
         :audited audited
         :verdict (cond
                    (and retained audited) :retained
                    (and audited (nil? retained)) :audit-only
                    (nil? audited) :no-approver)}))))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw-list [xs]
  (if (seq xs) (str/join ", " (map #(str (if (keyword? %) (name %) %)) xs)) "—"))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- span [cls v] (str "<span class=\"" cls "\">" v "</span>"))

(defn- tbl [head rows]
  (str "    <table>\n"
       "      <thead><tr>"
       (str/join (map #(str "<th>" % "</th>") head))
       "</tr></thead>\n      <tbody>\n"
       (str/join "\n" rows) "\n"
       "      </tbody>\n    </table>\n"))

(defn- section [title lede body]
  (str "  <section class=\"card\">\n    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lede "</p>\n" body "  </section>\n"))

(defn- row [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- disposition-cell [{:keys [disposition hard? violations phase-reason
                                approval ask-reason status]}]
  (cond
    (and (= :hold disposition) hard?)
    (span "critical" (str "HARD hold &middot; " (esc (kw-list (map :rule violations)))))

    (and (= :hold disposition) (= :rejected approval))
    (span "err" "hold &middot; approver rejected")

    (= :hold disposition)
    (span "err" (str "hold &middot; " (esc (name (or phase-reason :phase-gate)))))

    (= :interrupted status)
    (span "warn" (str "awaiting approval &middot; " (esc (name (or ask-reason :escalated)))))

    (and (= :commit disposition) approval)
    (span "ok" (str "approved &amp; committed &middot; " (esc (name (or ask-reason :escalated)))))

    (= :commit disposition) (span "ok" "auto-committed")
    :else (span "muted" (esc (str disposition)))))

(defn- application-rows [db]
  (let [ledger (store/ledger db)]
    (for [a (store/all-applications db)]
      (let [last-fact (last (filter #(= (:id a) (:subject %)) ledger))
            insured (store/party db (:insured a))
            kyc (store/kyc-of db (:insured a))]
        (row (code (:id a))
             (str (esc (:name insured "—")) " " (span "muted" (str "(" (esc (:insured a)) ")")))
             (kw-list (map #(str (:name (store/party db %)) " (" % ")") (:beneficiaries a)))
             (str (esc (:coverage-amount a)) " " (esc (:currency a)))
             (code (:jurisdiction a))
             (if (facts/spec-basis (:jurisdiction a))
               (span "ok" "registered")
               (span "critical" "no spec-basis"))
             (esc (name (:status a)))
             (if (:policy-number a) (code (:policy-number a)) (span "muted" "—"))
             (cond (nil? kyc) (span "muted" "insured not screened")
                   (= :clear (:verdict kyc)) (span "ok" "insured cleared")
                   :else (span "warn" (esc (name (:verdict kyc)))))
             (if last-fact
               (esc (name (:t last-fact)))
               (span "muted" "no activity"))
             (esc (kw-list (:basis last-fact))))))))

(defn- party-rows [db runs]
  (let [parties (:parties (store/demo-data))]
    (for [pid (sort (keys parties))]
      (let [p (store/party db pid)
            kyc (store/kyc-of db pid)
            r (last (filter #(and (= :kyc/screen (:op %)) (= pid (:subject %))) runs))]
        (row (code pid)
             (esc (:name p))
             (esc (name (:role p)))
             (if (:id-doc p) (code (:id-doc p)) (span "warn" "none on file"))
             (if (:sanctions-hit? p) (span "critical" "list match") (span "ok" "no match"))
             (if kyc
               (span (if (= :clear (:verdict kyc)) "ok" "warn") (esc (name (:verdict kyc))))
               (span "muted" "not committed"))
             (if r (disposition-cell r) (span "muted" "not screened in this run"))
             (if kyc
               (if (:approved-by kyc) (code (:approved-by kyc)) (span "muted" "—"))
               (span "muted" "—")))))))

(defn- jurisdiction-rows []
  (for [iso3 (sort (keys facts/catalog))]
    (let [j (facts/spec-basis iso3)]
      (row (code iso3)
           (esc (:name j))
           (esc (:owner-authority j))
           (esc (:legal-basis j))
           (str "<a href=\"" (esc (:provenance j)) "\">" (esc (:provenance j)) "</a>")
           (str (count (:required-docs j)) " docs")))))

(defn- phase-rows []
  (for [p (sort (keys phase/phases))]
    (let [{:keys [label writes auto]} (get phase/phases p)]
      (row (esc p)
           (esc label)
           (if (seq writes) (kw-list (sort writes)) (span "muted" "none"))
           (if (seq auto)
             (span "ok" (esc (kw-list (sort auto))))
             (span "warn" "none — every write needs a human"))
           (if (contains? auto :policy/bind)
             (span "critical" "INVARIANT BROKEN")
             (span "ok" "never auto"))))))

(defn- run-rows [runs]
  (for [r runs]
    (row (code (:thread r))
         (code (:op r))
         (code (:subject r))
         (esc (:phase r))
         (if-some [c (:confidence r)] (esc c) (span "muted" "—"))
         (disposition-cell r)
         (if (:approver r) (code (:approver r)) (span "muted" "—"))
         (span "muted" (esc (:note r))))))

(defn- hold-rows [db]
  (for [f (ledger-holds db)]
    (row (code (:op f))
         (code (:subject f))
         (if (some hard-rules (:basis f))
           (span "critical" "HARD — not overridable")
           (span "err" "hold"))
         (esc (kw-list (:basis f)))
         (kw-list (map :detail (:violations f)))
         (if-some [c (:confidence f)] (esc c) (span "muted" "—")))))

(defn- attribution-rows [rows]
  (for [{:keys [effect reader retained audited verdict]} rows]
    (row (code effect)
         (code reader)
         (if audited (code audited) (span "muted" "nobody was asked"))
         (if retained (code retained) (span "muted" "absent"))
         (case verdict
           :retained (span "ok" "retained in the committed record")
           :audit-only (span "warn" "audit only — not retained in record")
           :no-approver (span "muted" "auto-committed — there is no approver to retain")))))

(defn- binding-rows [db]
  (for [b (store/binding-history db)]
    (row (code (get b "record_id"))
         (esc (get b "kind"))
         (code (get b "insured"))
         (kw-list (map :id (get b "beneficiaries")))
         (str (esc (get b "coverage_amount")) " ")
         (code (get b "jurisdiction"))
         (if (get b "immutable") (span "ok" "append-only") (span "warn" "mutable"))
         (span "warn" "unsigned draft — signature is the licensed underwriter's act"))))

(defn- ledger-rows [db]
  (for [f (store/ledger db)]
    (row (esc (name (:t f)))
         (code (:op f))
         (code (:subject f))
         (esc (name (or (:disposition f) :n-a)))
         (esc (kw-list (:basis f)))
         (esc (or (:summary f) (kw-list (map :detail (:violations f))))))))

(defn render
  "Renders the whole document from `db` + `runs` as returned by `run-demo!`."
  [{:keys [db runs]}]
  (let [attribution (approver-attribution db runs)
        cov (facts/coverage)
        hard (observed-hard-rules db)]
    (str
     "<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
     "<title>cloud-itonami-isic-6511 &middot; life-insurance underwriting operator console</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Life insurance (ISIC 6511) — Underwriting Operator Console</h1>\n"
     "  <p class=\"badge\">read-only sample · every row below was produced by running "
     "<code>underwriting.operation</code> → <code>underwriting.governor</code> → "
     "<code>underwriting.store</code> at build time · no wall clock, no random ids</p>\n"
     "</header>\n"
     "<main>\n"

     (section
      "Applications (SSoT, after the run)"
      (str "Read back through the <code>Store</code> protocol once the scenario finished. "
           "Ids, parties, coverage amounts and jurisdictions are "
           "<code>underwriting.store/demo-data</code>; the spec-basis column is a lookup "
           "into <code>underwriting.facts/catalog</code>.")
      (tbl ["Application" "Insured" "Beneficiaries" "Coverage" "Jurisdiction"
            "Spec-basis" "Status" "Policy no." "KYC of insured" "Last ledger fact" "Basis"]
           (application-rows db)))

     (section
      "Parties &amp; committed KYC register"
      (str "The advisor screens locally first (identity document, sanctions flag) and only then "
           "consults the optional <code>underwriting.corporate-intel</code> cross-reference into "
           "cloud-itonami-isic-8291. A party with no committed verdict is one whose screening "
           "never earned the right to commit.")
      (tbl ["Party" "Name" "Role" "Identity document" "Local sanctions flag"
            "Committed verdict" "This run" "Approved by"]
           (party-rows db runs)))

     (section
      "Jurisdiction requirement catalog (spec-basis)"
      (str "<code>underwriting.facts/catalog</code> verbatim — the table the governor checks every "
           "<code>:jurisdiction/assess</code> proposal against. Coverage is reported honestly: "
           (:covered cov) " of " (:requested cov) " seeded jurisdictions have an official "
           "spec-basis. A jurisdiction that is absent has none, and the advisor is not allowed to "
           "invent one (see <code>app-2</code>, jurisdiction <code>ATL</code>, below).")
      (tbl ["ISO3" "Jurisdiction" "Owner authority" "Legal basis" "Provenance" "Required docs"]
           (jurisdiction-rows)))

     (section
      "Rollout phase gate"
      (str "<code>underwriting.phase/phases</code> verbatim. The phase can only make the actor MORE "
           "conservative than the governor, never less. <code>:policy/bind</code> is absent from every "
           "phase's auto set — the last column is computed from the table, so it would read "
           "<em>INVARIANT BROKEN</em> if someone added it.")
      (tbl ["Phase" "Label" "Writes allowed" "May auto-commit when governor-clean" ":policy/bind"]
           (phase-rows)))

     (section
      "Operations in this run"
      (str "One row per <code>langgraph</code> graph run. Disposition, confidence, escalation reason "
           "and violation rules are read out of the returned graph state — this table is the run, "
           "not a description of it.")
      (tbl ["Thread" "Op" "Subject" "Phase" "Confidence" "Disposition" "Approver" "What it demonstrates"]
           (run-rows runs)))

     (section
      "Governor holds (committed to the ledger)"
      (str "HARD rules exercised by this run: <strong>" (esc (kw-list hard)) "</strong> — "
           "which is every hard rule <code>underwriting.governor</code> implements. A HARD hold is "
           "never offered to a human: the actor does not pause at "
           "<code>:request-approval</code> at all, so there is no approval that could release it. "
           "The build refuses to write this page if this section comes out empty.")
      (tbl ["Op" "Subject" "Severity" "Rules" "Detail" "Advisor confidence"]
           (hold-rows db)))

     (section
      "Approver attribution (measured at render time)"
      (str "Approval arrives as <code>{:approval {:status :approved :by …}}</code> and the operation "
           "puts it on the record's <code>:payload</code>. Whether it is still readable afterwards "
           "depends on which key that effect's branch of <code>store/commit-record!</code> reads — so "
           "this table is produced by looking for an approver key in the register the store hands "
           "back, not by asserting anything about the store. Change the store and this table changes "
           "with it.")
      (tbl ["Effect" "Register read back" "Approver in audit" "Approver in record" "Retention"]
           (attribution-rows attribution)))

     (section
      "Policy-binding drafts"
      (str "<code>underwriting.registry</code> output, appended by <code>store/commit-record!</code> "
           "under <code>:policy/mark-bound</code>. The policy number is a jurisdiction-scoped sequence "
           "— this repo deliberately does not invent an international check-digit standard for "
           "life-insurance policy numbers, because there is none.")
      (tbl ["Policy no." "Kind" "Insured" "Beneficiaries" "Coverage" "Jurisdiction" "History" "Certificate"]
           (binding-rows db)))

     (section
      "Audit ledger (append-only)"
      (str "Every decision fact this run committed, in order. " (count (store/ledger db))
           " facts. Holds are recorded as durably as commits — a rejected proposal leaves evidence.")
      (tbl ["Fact" "Op" "Subject" "Disposition" "Basis" "Summary / detail"]
           (ledger-rows db)))

     "</main>\n"
     "<footer><p class=\"muted\">Generated by <code>underwriting.render-html</code> "
     "(<code>clojure -M:dev:render-html</code>) from a real actor run. "
     "Confidence floor <code>" (esc governor/confidence-floor) "</code>; high-stakes set <code>"
     (esc (kw-list (sort governor/high-stakes))) "</code>. This page is a sample, not advice, and "
     "binds no real coverage: every certificate this actor produces is an unsigned draft.</p></footer>\n"
     "</body></html>\n")))

;; ----------------------------- entry point -----------------------------

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        holds (ledger-holds db)
        governor-holds (filter #(= :governor-hold (:t %)) (store/ledger db))
        observed (observed-hard-rules db)]
    ;; Build-time invariants. A console that shows only clean commits would
    ;; be advertising: refuse to write one.
    (when (empty? governor-holds)
      (throw (ex-info "refusing to write the console: the run produced no :governor-hold fact"
                      {:ledger-facts (count (store/ledger db)) :runs (count runs)})))
    (when-not (= hard-rules (set observed))
      (throw (ex-info "refusing to write the console: not every HARD governor rule was exercised"
                      {:required hard-rules :observed observed
                       :missing (remove observed hard-rules)})))
    (when (empty? (store/binding-history db))
      (throw (ex-info "refusing to write the console: no policy binding was ever drafted"
                      {:runs (count runs)})))
    (spit out (render result))
    (println "wrote" out
             (str "(" (count runs) " runs, "
                  (count (store/ledger db)) " ledger facts, "
                  (count holds) " holds, hard rules exercised: "
                  (str/join "," (map name observed)) ")"))))
