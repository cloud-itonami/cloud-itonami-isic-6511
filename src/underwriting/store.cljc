(ns underwriting.store
  "SSoT for the underwriting actor, behind a `Store` protocol so the backend
  is a swap, not a rewrite -- the same seam `cloud-itonami-M6910`
  (`formation.store`) / `cloud-itonami-L6810` (`realty.store`) use:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/underwriting/store_contract_test.clj), which is the whole point:
  the actor, the UnderwritingGovernor and the audit ledger never know
  which SSoT they run on.

  The ledger stays append-only on every backend: 'who bound what, for
  which applicant, on what jurisdictional basis, approved by whom' is
  always a query over an immutable log -- the audit trail a policyholder
  trusting an operator with their coverage needs, and the evidence an
  operator needs if a binding is later disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [underwriting.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (application [s id])
  (all-applications [s])
  (party [s id])
  (kyc-of [s party-id] "committed KYC screening verdict for a party, or nil")
  (assessment-of [s app-id] "committed jurisdiction underwriting-requirement assessment, or nil")
  (ledger [s])
  (binding-history [s] "the append-only policy-binding history (underwriting.registry drafts)")
  (next-sequence [s jurisdiction] "next policy-number sequence for a jurisdiction")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-applications [s apps] "replace/seed the application directory (map id->application)")
  (with-parties [s parties] "replace/seed the party directory (map id->party)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained applicant set so the actor + tests run offline."
  []
  {:applications
   {"app-1" {:id "app-1" :insured "party-1" :beneficiaries ["party-2"]
             :coverage-amount 50000000 :currency "JPY" :jurisdiction "JPN" :status :intake}
    "app-2" {:id "app-2" :insured "party-3" :beneficiaries ["party-4"]
             :coverage-amount 100 :currency "USD" :jurisdiction "ATL" :status :intake}}
   :parties
   {"party-1" {:id "party-1" :name "田中 花子" :role :insured :sanctions-hit? false :id-doc "passport-jp-****9012"}
    "party-2" {:id "party-2" :name "田中 一郎" :role :beneficiary :sanctions-hit? false :id-doc "passport-jp-****1234"}
    "party-3" {:id "party-3" :name "J. Doe" :role :insured :sanctions-hit? true :id-doc nil}
    "party-4" {:id "party-4" :name "J. Doe Jr." :role :beneficiary :sanctions-hit? false :id-doc nil}}})

;; ----------------------------- shared binding logic -----------------------------

(defn- bind!
  "Backend-agnostic `:policy/mark-bound` -- looks up the application + its
  parties via the protocol, drafts the policy-binding record, and returns
  {:result .. :app-patch ..} for the caller to persist. Pure w.r.t. any
  particular backend's transaction mechanics."
  [s app-id]
  (let [a (application s app-id)
        seq-n (next-sequence s (:jurisdiction a))
        result (registry/register-binding
                (:insured a) (mapv #(party s %) (:beneficiaries a))
                (:coverage-amount a) (:jurisdiction a) seq-n)]
    {:result result
     :app-patch {:status :bound
                 :policy-number (get result "policy_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (application [_ id] (get-in @a [:applications id]))
  (all-applications [_] (sort-by :id (vals (:applications @a))))
  (party [_ id] (get-in @a [:parties id]))
  (kyc-of [_ id] (get-in @a [:kyc id]))
  (assessment-of [_ app-id] (get-in @a [:assessments app-id]))
  (ledger [_] (:ledger @a))
  (binding-history [_] (:bindings @a))
  (next-sequence [_ jurisdiction]
    (get-in @a [:sequences jurisdiction] 0))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :application/upsert
      (swap! a update-in [:applications (:id value)] merge value)

      :assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :kyc/set
      (swap! a assoc-in [:kyc (first path)] payload)

      :policy/mark-bound
      (let [app-id (first path)
            {:keys [result app-patch]} (bind! s app-id)]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:sequences (:jurisdiction (get-in state [:applications app-id]))] (fnil inc 0))
                       (update-in [:applications app-id] merge app-patch)
                       (update :bindings registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-applications [s apps] (when (seq apps) (swap! a assoc :applications apps)) s)
  (with-parties [s parties] (when (seq parties) (swap! a assoc :parties parties)) s))

(defn seed-db
  "A MemStore seeded with the demo applicant set. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {} :kyc {} :ledger [] :sequences {} :bindings []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (beneficiary id lists, KYC/assessment payloads,
  ledger facts, binding records) are stored as EDN strings so
  `langchain.db` doesn't expand them into sub-entities -- the same
  convention `formation.store` / `realty.store` use."
  {:app/id            {:db/unique :db.unique/identity}
   :party/id          {:db/unique :db.unique/identity}
   :kyc/party-id      {:db/unique :db.unique/identity}
   :assessment/app-id {:db/unique :db.unique/identity}
   :ledger/seq        {:db/unique :db.unique/identity}
   :binding/seq       {:db/unique :db.unique/identity}
   :sequence/jurisdiction {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- app->tx [{:keys [id insured beneficiaries coverage-amount currency
                        jurisdiction status policy-number]}]
  (cond-> {:app/id id}
    insured          (assoc :app/insured insured)
    beneficiaries    (assoc :app/beneficiaries (enc beneficiaries))
    coverage-amount  (assoc :app/coverage-amount coverage-amount)
    currency         (assoc :app/currency currency)
    jurisdiction     (assoc :app/jurisdiction jurisdiction)
    status           (assoc :app/status status)
    policy-number    (assoc :app/policy-number policy-number)))

(def ^:private app-pull
  [:app/id :app/insured :app/beneficiaries :app/coverage-amount :app/currency
   :app/jurisdiction :app/status :app/policy-number])

(defn- pull->app [m]
  (when (:app/id m)
    {:id (:app/id m) :insured (:app/insured m)
     :beneficiaries (or (dec* (:app/beneficiaries m)) [])
     :coverage-amount (:app/coverage-amount m) :currency (:app/currency m)
     :jurisdiction (:app/jurisdiction m) :status (:app/status m)
     :policy-number (:app/policy-number m)}))

(defn- party->tx [{:keys [id name role sanctions-hit? id-doc]}]
  (cond-> {:party/id id}
    name (assoc :party/name name)
    role (assoc :party/role role)
    (some? sanctions-hit?) (assoc :party/sanctions-hit? sanctions-hit?)
    id-doc (assoc :party/id-doc id-doc)))

(defn- pull->party [m]
  (when (:party/id m)
    {:id (:party/id m) :name (:party/name m) :role (:party/role m)
     :sanctions-hit? (boolean (:party/sanctions-hit? m)) :id-doc (:party/id-doc m)}))

(defrecord DatomicStore [conn]
  Store
  (application [_ id]
    (pull->app (d/pull (d/db conn) app-pull [:app/id id])))
  (all-applications [_]
    (->> (d/q '[:find [?id ...] :where [?e :app/id ?id]] (d/db conn))
         (map #(pull->app (d/pull (d/db conn) app-pull [:app/id %])))
         (sort-by :id)))
  (party [_ id]
    (pull->party (d/pull (d/db conn)
                         [:party/id :party/name :party/role :party/sanctions-hit? :party/id-doc]
                         [:party/id id])))
  (kyc-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?pid
                :where [?k :kyc/party-id ?pid] [?k :kyc/payload ?p]]
              (d/db conn) id)))
  (assessment-of [_ app-id]
    (dec* (d/q '[:find ?p . :in $ ?aid
                :where [?a :assessment/app-id ?aid] [?a :assessment/payload ?p]]
              (d/db conn) app-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (binding-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :binding/seq ?s] [?e :binding/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :sequence/jurisdiction ?j] [?e :sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :application/upsert
      (d/transact! conn [(app->tx value)])

      :assessment/set
      (d/transact! conn [{:assessment/app-id (first path) :assessment/payload (enc payload)}])

      :kyc/set
      (d/transact! conn [{:kyc/party-id (first path) :kyc/payload (enc payload)}])

      :policy/mark-bound
      (let [app-id (first path)
            {:keys [result app-patch]} (bind! s app-id)
            jurisdiction (:jurisdiction (application s app-id))
            next-n (inc (next-sequence s jurisdiction))]
        (d/transact! conn
                     [(app->tx (assoc app-patch :id app-id))
                      {:sequence/jurisdiction jurisdiction :sequence/next next-n}
                      ;; store just the "record" sub-map, matching MemStore's
                      ;; `registry/append` convention -- binding-history is a
                      ;; history of RECORDS, not of the full binding result.
                      {:binding/seq (count (binding-history s)) :binding/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-applications [s apps]
    (when (seq apps) (d/transact! conn (mapv app->tx (vals apps)))) s)
  (with-parties [s parties]
    (when (seq parties) (d/transact! conn (mapv party->tx (vals parties)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:applications .. :parties ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [applications parties]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (-> s (with-applications applications) (with-parties parties)))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo applicant set -- the Datomic-backed
  analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
