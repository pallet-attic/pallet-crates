(ns pallet.crate.couchdb-test
  (:use pallet.crate.couchdb)
  (:require
   [pallet.build-actions :as build-actions]
   [pallet.stevedore :as stevedore])
  (:use clojure.test
        pallet.test-utils))

(deftest couchdb-test
  (testing "invocation"
    (is (build-actions/build-actions
         {}
         (install)
         (configure {})
         (configure {[:a :b] "value"})
         (couchdb)))))
