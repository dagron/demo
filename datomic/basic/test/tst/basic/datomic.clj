(ns tst.basic.datomic
  (:use basic.datomic
        cooljure.core
        clojure.test )
  (:require [datomic.api      :as d]
            [schema.core      :as s]))

(def ^:dynamic *tst-conn*)

(use-fixtures :once 
  (fn [tst-fn]
    ; Create the database & a connection to it
    (let [uri           "datomic:mem://testing"
          _  (d/create-database uri)
          conn          (d/connect uri)
          schema-defs   (read-string (slurp "ex-schema.edn")) ; Load schema defs from file
    ]
      @(d/transact conn schema-defs)
      (binding [*tst-conn* conn]
        (tst-fn))
      (d/delete-database uri))))


(deftest t-create-attribute-map
  (let [result  (create-attribute-map :weapon/type :db.type/keyword 
                    :db.unique/value       :db.unique/identity 
                    :db.cardinality/one    :db.cardinality/many 
                    :db/index :db/fulltext :db/isComponent :db/noHistory ) ]
    (spyx result)
    (is (truthy? (:db/id result)))
    (is (=  (dissoc result :db/id)  ; remove volatile tempid
            {:db/index true  :db/unique :db.unique/identity  :db/valueType :db.type/keyword 
             :db/noHistory true  :db/isComponent true  :db.install/_attribute :db.part/db 
             :db/fulltext true  :db/cardinality :db.cardinality/many  :db/ident :weapon/type} )))
  (let [result  (create-attribute-map :weapon/type :db.type/keyword 
                    :db.unique/identity    :db.unique/value
                    :db.cardinality/many   :db.cardinality/one
                    :db/index :db/fulltext :db/isComponent :db/noHistory ) ]
    (spyx result)
    (is (truthy? (:db/id result)))
    (is (=  (dissoc result :db/id)  ; remove volatile tempid
            {:db/index true  :db/unique :db.unique/value  :db/valueType :db.type/keyword 
             :db/noHistory true  :db/isComponent true  :db.install/_attribute :db.part/db 
             :db/fulltext true  :db/cardinality :db.cardinality/one  :db/ident :weapon/type} ))
  ))
         