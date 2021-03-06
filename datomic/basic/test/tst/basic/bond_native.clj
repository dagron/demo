(ns tst.basic.bond-native
  (:require [datomic.api      :as d]
            [schema.core      :as s]
            [tupelo.core      :refer [spy spyx spyxx it-> safe-> matches? grab wild-match? forv submap? ]]
            [tupelo.datomic   :as td]
            [tupelo.schema    :as ts]
  )
  (:use clojure.pprint
        clojure.test
        tupelo.core)
  (:gen-class))

(set! *warn-on-reflection* false)
(set! *print-length* nil)
(s/set-fn-validation! true)             ; enable Prismatic Schema type definitions (#todo add to Schema docs)

(def uri "datomic:mem://bond")          ; the URI for our test db
(def ^:dynamic *conn*)                  ; dynamic var to hold the db connection
(use-fixtures :each
  (fn [tst-fn]
    (d/create-database uri)             ; create the DB
    (binding [*conn* (d/connect uri) ]  ; create & save a connection to the db
      (tst-fn))
    (d/delete-database uri)))

;---------------------------------------------------------------------------------------------------
; helper functions
(defn get-people
  "Returns facts about all entities with the :person/name attribute"
  [db-val]
  (let [eid-set     (td/query-set :let    [$ db-val]
                                  :find   [?e]  ; <- could also use Datomic Pull API
                                  :where  [ [?e :person/name] ] ) ]
    (into #{}
      (for [eid eid-set]
        (td/entity-map db-val eid)))))

(defn show-transactions
  "Display all transactions in the DB"
  [db-val]
  (println "-----------------------------------------------------------------------------")
  (println "Database Transactions:")
  (let [all-tx      (td/transactions db-val)
        sorted-tx   (sort-by #(grab :db/txInstant %) all-tx) ]
    (doseq [it sorted-tx]
      (pprint it))))

(defn trunc-str [-str -chars]
  (apply str (take -chars -str)))

;---------------------------------------------------------------------------------------------------

(deftest t-james-bond
  ; Create a partition named :people (we could namespace it like :db.part/people if we wished)
  (td/transact *conn* 
    (td/new-partition :people ))

  ; Create some attribute definitions. We use a keyword as the attribute's name (it's :db/ident
  ; value). The attribute name may be namespaced like :person/name or it could be a plain keyword
  ; like :location. This keyword-name can be anything (it is not predefined anywhere).
  (td/transact *conn*  ;  required              required               zero-or-more
                     ; <attr name>         <attr value type>       <optional specs ...>
    (td/new-attribute :person/name         :db.type/string         :db.unique/value)      ; each name      is unique
    (td/new-attribute :person/secret-id    :db.type/long           :db.unique/value)      ; each secret-id is unique
    (td/new-attribute :weapon/type         :db.type/ref            :db.cardinality/many)  ; one may have many weapons
    (td/new-attribute :location            :db.type/string)     ; all default values
    (td/new-attribute :favorite-weapon     :db.type/keyword ))  ; all default values
          ; Note that an :db.type/keyword attribue (like :favorite-weapon) is very similar to an string. Any
          ; keyword can be added here.  Example error is to add the keyword :location or :person/secret-id
          ; or :there.is/no-such-kw. It is really just like a string, where anything is accepted. If you
          ; want to enforce a limited set of values (or any other rules/invariants) you must write your own
          ; functions to enforce it.
  ; #todo come back and change -> :db.type/ref

  ; Create some "enum" values. These are degenerate entities (which therefore have an EID) that
  ; serve the same purpose as an (integer) enumerated value in Java (these entities will never
  ; have any attributes).
  (td/transact *conn* 
    (td/new-enum :weapon/gun)
    (td/new-enum :weapon/knife)
    (td/new-enum :weapon/guile)
    (td/new-enum :weapon/wit))

  ; Create some antagonists and load them into the db.  We can specify some of the attribute-value
  ; pairs at the time of creation, and add others later. Note that whenever we are adding multiple
  ; values for an attribute in a single step (e.g. :weapon/type), we must wrap all of the values
  ; in a set. Howevever, the set implies there can never be duplicates.
  (td/transact *conn* 
    (td/new-entity { :person/name "James Bond" :location "London"     :weapon/type #{ :weapon/gun :weapon/wit   } } )
    (td/new-entity { :person/name "M"          :location "London"     :weapon/type #{ :weapon/gun :weapon/guile } } )
    (td/new-entity { :person/name "Dr No"      :location "Caribbean"  :weapon/type    :weapon/gun                 } ))

  (newline) (println "db 00")
  (pprint (get-people (d/db *conn*)))
  (let [people (get-people (d/db *conn*)) ]
    (is (= people   
           #{ {:person/name "James Bond"    :location "London"      :weapon/type #{:weapon/wit    :weapon/gun} }
              {:person/name "M"             :location "London"      :weapon/type #{:weapon/guile  :weapon/gun} }
              {:person/name "Dr No"         :location "Caribbean"   :weapon/type #{:weapon/gun               } } } )))

  ; Verify we can find James by name 
  (let [db-val      (d/db *conn*)
        ; find James' EntityId (EID). It is a Long that is a unique ID across the whole DB
        james-eid   (td/query-scalar  :let    [$ db-val]
                                      :find   [?eid]
                                      :where  [ [?eid :person/name "James Bond"] ] )
        ; get all of James' attr-val pairs as a clojure map
        james-map   (td/entity-map db-val james-eid) ]
    (is (s/validate ts/Eid james-eid))    ; verify eid (it is a Long)
    (is (pos? (long james-eid)))          ; eids are always positive (temp eids are negative)
    (is (= james-map {:person/name "James Bond" :location "London" :weapon/type #{:weapon/wit :weapon/gun} } ))

    ; Update the database with more weapons.  If we overwrite some items that are already present
    ; (e.g. :weapon/gun) it is idempotent (no duplicates are allowed).  The first arg to td/update
    ; is an EntitySpec and determines the Entity that is updated. This is either (1) and EntityId
    ; (EID) or (2) a LookupRef.
    (td/transact *conn* 
      (td/update james-eid   ; Here we use the eid we found earlier as a "pointer" to James
          { :weapon/type #{ :weapon/gun :weapon/knife }
            :person/secret-id 007 } )   ; It is OK if James has a secret-id but no one else does

      ; Here we use a LookupRef, which is any attr-val pair with :db.unique/value or :db.unique/identity
      (td/update [:person/name "Dr No"]
        { :weapon/type #{ :weapon/gun :weapon/knife :weapon/guile } } )))

  ; Verify current status. Notice there are no duplicate weapons.
  (let [people (get-people (d/db *conn*)) ]
    (is (= people   
      #{ {:person/name "James Bond" :location "London"    :weapon/type #{              :weapon/wit :weapon/knife :weapon/gun} :person/secret-id 7 }
         {:person/name "M"          :location "London"    :weapon/type #{:weapon/guile                           :weapon/gun} }
         {:person/name "Dr No"      :location "Caribbean" :weapon/type #{:weapon/guile             :weapon/knife :weapon/gun} } } )))

  (newline)
  (println "basic usage")

  ; result is a set - discards duplicates
  (let [find-loc-entity (td/query-set :let    [$ (d/db *conn*)]   ; $ is the implicit db name
                                      :find   [?loc]            ; variables from the :where clause to output
                                      :where  [ [?eid :location ?loc] ] ) ; multiple match patterns, as a vector-of-vectors
  ]
    (is (s/validate #{s/Str} find-loc-entity))    ; a set of strings
    (is (= find-loc-entity #{"Caribbean" "London"} )))

  ; result is a list made from a set - discards duplicates
  (def find-loc-coll
    (d/q  '{:find [ [?loc ...] ]
            :where [ [?eid :location ?loc] ] }
         (d/db *conn*)))
  (spyxx find-loc-coll)

  ; result is a list - retains duplicates
  (def find-pull
    (d/q  '{:find   [ (pull ?eid [:location]) ]
            :where  [ [?eid :location] ] }
         (d/db *conn*)))
  (spyxx find-pull)

  (def find-pull2 (into #{} find-pull))
  (spyxx find-pull2)

  ; shows some problems

  ; silently discards all but first location
  (let [single-tuple    (d/q  '{:find [ [?loc] ]
                                :where [ [?eid :location ?loc] ] }
                             (d/db *conn*)) ]
    (spyxx single-tuple))
  ;
  ; silently discards all but first location
  (let [single-scalar   (d/q  '{:find [?loc .]
                                :where [ [?eid :location ?loc] ] }
                             (d/db *conn*)) ]
    (spyxx single-scalar))

  (newline)
  (println "show problems")

  ; silently discards all but first location
  (let [single-tuple    (d/q  '{:find [ [?loc] ]
                                :where [ [?eid :location ?loc] ] }
                             (d/db *conn*)) ]
    (spyxx single-tuple))
  ;
  ; silently discards all but first location
  (let [single-scalar   (d/q  '{:find [?loc .]
                                :where [ [?eid :location ?loc] ] }
                             (d/db *conn*)) ]
    (spyxx single-scalar))

  (newline)
  (println "finding name & loc")
  ; result is a set - discards duplicates
  (let [find-name-loc-entity
              (d/q  '{:find [?name ?loc]
                      :where [ [?eid :location    ?loc] 
                               [?eid :person/name ?name] ] }
                   (d/db *conn*)) ]
    (spyxx find-name-loc-entity))

  ; result is a list - retains duplicates
  (let [find-name-loc-pull
              (d/q  '{:find   [ (pull ?eid [:person/name :location]) ]
                      :where [ [?eid :location] ] }
                   (d/db *conn*)) 
        find-name-loc-pull2 (into #{} find-name-loc-pull) ]
    (spyxx find-name-loc-pull)
    (spyxx find-name-loc-pull2))

  (newline)
  (println "pulling with defaults")
  (let [result    (d/q  '{:find   [ (pull ?eid [:person/name (default :person/secret-id -1) ] ) ]
                          :where  [ [?eid :person/name ?name] ] }
                    (d/db *conn*))
  ]
    (pprint result))
  (println "pulling without defaults")
  (let [result    (d/q  '{:find   [ (pull ?eid [:person/name :person/secret-id] ) ]
                          :where  [ [?eid :person/name ?name] ] }
                    (d/db *conn*))
  ]
    (pprint result))

  (newline) 
  (println "Trying to add non-existent weapon")
  (spyxx
    @(td/transact *conn* 
      (td/update
        [:person/name "James Bond"]
        { :favorite-weapon :there.is/no-such-kw } )))  ; works just fine


  (newline) (println "db 02")
  (pprint (get-people (d/db *conn*)))

  (d/delete-database uri)
)
