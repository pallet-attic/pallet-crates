(ns pallet.crate.wordpress-test
  (:use
   pallet.crate.wordpress
   clojure.test
   pallet.test-utils)
  (:require
   [pallet.crate.mysql :as mysql]
   [pallet.build-actions :as build-actions]))

(deftest invoke-test
  (is (build-actions/build-actions
       {}
       (mysql/mysql-server "pw")
       (wordpress
        "mysql-wp-username" "mysql-wp-password" "mysql-wp-database")
       (wordpress
        "mysql-wp-username" "mysql-wp-password" "mysql-wp-database"
        "extension"))))
