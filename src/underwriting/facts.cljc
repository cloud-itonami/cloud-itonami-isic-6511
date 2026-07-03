(ns underwriting.facts
  "Per-jurisdiction life-insurance underwriting requirement catalog -- the
  G2-style spec-basis table the UnderwritingGovernor checks every
  jurisdiction/assess proposal against ('did the advisor cite an OFFICIAL
  public source for this jurisdiction's requirements, or did it invent
  one?').

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  `cloud-itonami-M6910`'s `formation.facts` and `cloud-itonami-L6810`'s
  `realty.facts` use: a jurisdiction not in this table has NO spec-basis,
  full stop -- the advisor must not fabricate one, and the governor holds
  if it tries.

  Seed values are drawn from each jurisdiction's official insurance
  regulator (see `:provenance`); they are a STARTING catalog, not a
  from-scratch survey of all ~194 jurisdictions. Extending coverage is
  additive: add one map to `catalog`, cite a real source, done -- never
  invent a jurisdiction's requirements to make coverage look bigger.")

(def catalog
  "iso3 -> requirement map. `:required-docs` mirrors the generic
  underwriting checklist a life insurer asks for in some form;
  `:legal-basis` / `:owner-authority` / `:provenance` are the G2 citation
  the governor requires before any :jurisdiction/assess proposal can
  commit."
  {"JPN" {:name "Japan"
          :owner-authority "金融庁 (Financial Services Agency)"
          :legal-basis "保険業法 (Insurance Business Act)"
          :national-spec "生命保険協会 標準約款 (Life Insurance Association model policy conditions)"
          :provenance "https://www.fsa.go.jp/"
          :required-docs ["告知書 (health declaration)"
                          "本人確認書類"
                          "収入・資産の確認書類 (income/asset justification for coverage amount)"
                          "受取人指定書類 (beneficiary designation)"]}
   "USA-NY" {:name "United States -- New York (exemplar; federalism note below)"
             :owner-authority "New York State Department of Financial Services (NYDFS)"
             :legal-basis "New York Insurance Law"
             :national-spec "NYDFS life insurance regulation"
             :provenance "https://www.dfs.ny.gov/"
             :notes "No federal insurance regulator -- life insurance is regulated per-state; New York is an exemplar, not a national authority."
             :required-docs ["Application for life insurance"
                             "Medical Information Bureau (MIB) check consent"
                             "Attending Physician Statement (if triggered by health disclosure)"
                             "Illustration/disclosure statement"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "Financial Conduct Authority (FCA) / Prudential Regulation Authority (PRA)"
          :legal-basis "Financial Services and Markets Act 2000"
          :national-spec "FCA Consumer Duty / ICOBS conduct rules"
          :provenance "https://www.fca.org.uk/"
          :required-docs ["Application/proposal form"
                          "Medical declaration"
                          "Identity verification"
                          "Key features document"]}
   "DEU" {:name "Germany"
          :owner-authority "Bundesanstalt für Finanzdienstleistungsaufsicht (BaFin)"
          :legal-basis "Versicherungsvertragsgesetz (VVG)"
          :national-spec "VVG-Informationspflichtenverordnung (disclosure-duties regulation)"
          :provenance "https://www.bafin.de/"
          :required-docs ["Antragsformular (application form)"
                          "Gesundheitsfragen (health questionnaire)"
                          "Identitätsnachweis"
                          "Produktinformationsblatt (product information sheet)"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to bind coverage on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-6511 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `underwriting.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-docs-satisfied?
  "Does `submitted` (a set/coll of doc keywords or strings) satisfy every
  required doc listed for `iso3`? Missing spec-basis -> never satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-docs]} (spec-basis iso3)]
    (let [need (count required-docs)
          have (count (filter (set submitted) required-docs))]
      (= need have))))

(defn doc-checklist [iso3]
  (:required-docs (spec-basis iso3) []))
