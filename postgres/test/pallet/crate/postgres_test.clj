(ns pallet.crate.postgres-test
  (:require
   [pallet.build-actions :as build-actions]
   [pallet.test-utils :as test-utils])
  (:use
   pallet.crate.postgres
   clojure.test))

(deftest postgres-test
  (is ; just check for compile errors for now
   (build-actions/build-actions
    {}
    (postgres "8.0")
    (postgres "9.0")
    (hba-conf :records [])
    (postgresql-script "some script")
    (create-database "db")
    (create-role "user"))))
