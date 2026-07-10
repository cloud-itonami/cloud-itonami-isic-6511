(ns underwriting.underwriterllm
  "Underwriter-LLM client -- the *contained intelligence node*.

  It normalizes application intake, drafts a per-jurisdiction
  underwriting-document checklist, screens applicants/beneficiaries
  against a KYC/sanctions signal, and drafts the policy-binding action.
  CRITICAL: it is a smart-but-untrusted advisor. It returns a *proposal*
  (with a rationale + the fields it cited), never a committed record or a
  real policy binding. Every output is censored downstream by
  `underwriting.governor` before anything touches the SSoT, and
  `:policy/bind` proposals NEVER auto-commit at any phase -- see README
  `Actuation`.

  Like `formation.registrarllm` / `realty.realtorllm`, this is a
  deterministic mock so the actor graph runs offline and the governor
  contract is exercised end-to-end. In production this calls a real LLM
  (kotoba-llm or equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation if it touches a real binding
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [underwriting.facts :as facts]
            [underwriting.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it does
  not invent parties, coverage amount or jurisdiction. High confidence,
  low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "申込レコード更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :application/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-jurisdiction
  "Per-jurisdiction underwriting-document checklist draft. `:no-spec?`
  injects the failure mode we must defend against: proposing a checklist
  for a jurisdiction with NO official spec-basis in `underwriting.facts`
  -- the UnderwritingGovernor must reject this (never invent a
  jurisdiction's law)."
  [db {:keys [subject no-spec?]}]
  (let [a (store/application db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction a))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "underwriting.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-docs sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :assessment/set
       :value      {:jurisdiction iso3
                    :checklist (:required-docs sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(def default-corporate-intel-screen
  "No-op corporate-intelligence cross-reference: always 'nothing on file'.
  This is the default so every existing caller of `screen-kyc`/`infer`/
  `mock-advisor` keeps its exact prior behavior unless it explicitly wires
  in `underwriting.corporate-intel/screen` (or an equivalent). Not required
  from this namespace directly -- keeping the dependency optional at the
  underwriterllm level, injected only by whoever builds the advisor."
  (constantly {:found? false :hit? false}))

(defn- screen-kyc
  "KYC / sanctions screening draft. `:sanctions-hit?` on the party record
  injects the failure mode: the UnderwritingGovernor must HOLD,
  un-overridably, on any sanctions/PEP hit. Missing identification yields
  low confidence -> escalate rather than auto-clear.

  `screen-fn` (party name -> corporate-intel result, see
  `underwriting.corporate-intel/screen`) is consulted ONLY once the local
  checks are otherwise clean -- it can turn a would-be :clear into :hit or
  :incomplete, but a local sanctions-hit or missing id-doc is decided
  first, cheaply, without depending on an external actor at all."
  [db {:keys [subject]} screen-fn]
  (let [p (store/party db subject)]
    (cond
      (nil? p)
      {:summary "対象partyが見つかりません" :rationale "no party record"
       :cites [] :effect :kyc/set :value {:party-id subject :verdict :unknown}
       :stake nil :confidence 0.0}

      (:sanctions-hit? p)
      {:summary    (str (:name p) ": 制裁/PEPリストと一致")
       :rationale  "スクリーニングが一致を検出。人手確認とホールドが必須。"
       :cites      [:sanctions-list]
       :effect     :kyc/set
       :value      {:party-id subject :verdict :hit}
       :stake      nil
       :confidence 0.95}

      (nil? (:id-doc p))
      {:summary    (str (:name p) ": 本人確認書類が未提出")
       :rationale  "本人確認書類が無いため確信度を上げられない。"
       :cites      [:id-doc]
       :effect     :kyc/set
       :value      {:party-id subject :verdict :incomplete}
       :stake      nil
       :confidence 0.4}

      :else
      (let [ci (screen-fn (:name p))]
        (cond
          (:hit? ci)
          {:summary    (str (:name p) ": corporate-intelligence 照会で制裁/PEPフラグを検出")
           :rationale  "cloud-itonami-isic-8291 の名前スクリーニングが一致を検出。人手確認とホールドが必須。"
           :cites      [:corporate-intelligence]
           :effect     :kyc/set
           :value      {:party-id subject :verdict :hit}
           :stake      nil
           :confidence 0.9}

          (:pending-human-review? ci)
          {:summary    (str (:name p) ": corporate-intelligence 照会が人手レビュー待ち")
           :rationale  "cloud-itonami-isic-8291 側の DisclosureGovernor が high-stakes escalate 中。確定するまでクリアにできない。"
           :cites      [:corporate-intelligence]
           :effect     :kyc/set
           :value      {:party-id subject :verdict :incomplete}
           :stake      nil
           :confidence 0.5}

          (:held? ci)
          {:summary    (str (:name p) ": corporate-intelligence 照会が拒否された(契約/設定の問題)")
           :rationale  (str "cloud-itonami-isic-8291 の DisclosureGovernor が本テナントの照会を拒否: " (pr-str (:reason ci)))
           :cites      [:corporate-intelligence]
           :effect     :kyc/set
           :value      {:party-id subject :verdict :incomplete}
           :stake      nil
           :confidence 0.4}

          :else
          {:summary    (str (:name p) ": 制裁リスト一致なし、本人確認書類あり")
           :rationale  "本人確認書類確認 + 制裁リスト非一致 + corporate-intelligence 照会クリア(または未収載)。"
           :cites      [:id-doc :sanctions-list :corporate-intelligence]
           :effect     :kyc/set
           :value      {:party-id subject :verdict :clear}
           :stake      nil
           :confidence 0.9})))))

(defn- propose-bind
  "Draft the actual policy-binding action -- issuing real life-insurance
  coverage. ALWAYS `:stake :actuation` -- this is a REAL-WORLD act (a
  policyholder becomes covered, premium obligations begin), never a draft
  the actor may auto-run. See README `Actuation`: no phase ever adds this
  op to a phase's `:auto` set (`underwriting.phase`); the governor also
  always escalates on `:actuation`. Two independent layers agree,
  deliberately."
  [db {:keys [subject]}]
  (let [a (store/application db subject)
        assessment (store/assessment-of db subject)
        docs-ok? (and assessment (facts/required-docs-satisfied?
                                  (:jurisdiction a)
                                  (:checklist assessment)))]
    {:summary    (str (:insured a) " (" (:jurisdiction a)
                      ") の成立準備ができました" (when-not docs-ok? " (書類未充足)"))
     :rationale  (if assessment
                   (str "spec-basis: " (:spec-basis assessment))
                   "assessment未実施")
     :cites      (if assessment [(:spec-basis assessment)] [])
     :effect     :policy/mark-bound
     :value      {:application-id subject}
     :stake      :actuation
     :confidence (if docs-ok? 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}
  `screen-fn` (default: `default-corporate-intel-screen`, a no-op) is only
  consulted by `:kyc/screen`, once local checks are otherwise clean."
  ([db request] (infer db request default-corporate-intel-screen))
  ([db {:keys [op] :as request} screen-fn]
   (case op
     :application/intake   (normalize-intake db request)
     :jurisdiction/assess  (assess-jurisdiction db request)
     :kyc/screen           (screen-kyc db request screen-fn)
     :policy/bind          (propose-bind db request)
     {:summary "未対応の操作" :rationale (str op) :cites []
      :effect :noop :stake nil :confidence 0.0})))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere.
  opts:
    :corporate-intel-screen -- party name -> corporate-intel result (see
      `underwriting.corporate-intel/screen`). Default: no-op (never changes
      a screen-kyc verdict), so `(mock-advisor)` with no args keeps every
      existing caller's exact prior behavior."
  ([] (mock-advisor {}))
  ([{:keys [corporate-intel-screen]
     :or   {corporate-intel-screen default-corporate-intel-screen}}]
   (reify Advisor (-advise [_ st req] (infer st req corporate-intel-screen)))))

(def ^:private system-prompt
  (str "あなたは生命保険引受(アンダーライティング)エージェントの助言者です。与えられた事実のみに"
       "基づき、提案を1つだけEDNマップで返します。説明や前置きは一切書かず、"
       "EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:application/upsert|:assessment/set|:kyc/set|:policy/mark-bound) "
       ":stake(:actuation か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :jurisdiction/assess {:application (store/application st subject)}
    :kyc/screen          {:party (store/party st subject)}
    :policy/bind         {:application (store/application st subject)
                          :assessment (store/assessment-of st subject)}
    {:application (store/application st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure yields
  a safe low-confidence noop so the UnderwritingGovernor escalates/holds --
  an LLM hiccup can never auto-bind coverage."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :underwriterllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
