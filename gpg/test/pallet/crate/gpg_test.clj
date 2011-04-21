(ns pallet.crate.gpg-test
  (:use pallet.crate.gpg)
  (:require
   [pallet.build-actions :as build-actions])
  (:use clojure.test
        pallet.test-utils))

(deftest invoke-test
  (is
   (build-actions/build-actions
    {}
    (gpg)
    (import-key :content "not ans export" :user "fred"))))
