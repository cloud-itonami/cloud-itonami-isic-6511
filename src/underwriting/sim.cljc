(ns underwriting.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean application through
  intake -> jurisdiction underwriting assessment -> KYC screening ->
  policy-bind proposal (always escalates) -> human approval -> commit,
  then shows a HARD hold (a sanctions hit) that never reaches a human at
  all, and prints the audit ledger + the draft policy-binding record."
  (:require [langgraph.graph :as g]
            [underwriting.store :as store]
            [underwriting.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :underwriter :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== intake app-1 (JPN, clean parties) ==")
    (println (exec! actor "t1" {:op :application/intake :subject "app-1"
                                :patch {:id "app-1" :status :ready}} operator))

    (println "== jurisdiction/assess app-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :jurisdiction/assess :subject "app-1"} operator))
    (println (approve! actor "t2"))

    (println "== kyc/screen party-1 (clean; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :kyc/screen :subject "party-1"} operator))
    (println (approve! actor "t3"))

    (println "== policy/bind app-1 (always escalates -- actuation) ==")
    (let [r (exec! actor "t4" {:op :policy/bind :subject "app-1"} operator)]
      (println r)
      (println "-- human operator (licensed underwriter) approves --")
      (println (approve! actor "t4")))

    (println "== kyc/screen party-3 (sanctions hit -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t5" {:op :kyc/screen :subject "party-3"} operator))

    (println "== jurisdiction/assess app-2 (no spec-basis -> HARD hold) ==")
    (println (exec! actor "t6" {:op :jurisdiction/assess :subject "app-2" :no-spec? true} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft policy-binding records ==")
    (doseq [r (store/binding-history db)] (println r))))
