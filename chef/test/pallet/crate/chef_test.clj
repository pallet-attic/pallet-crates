(ns pallet.crate.chef-test
  (:require
   [pallet.build-actions :as build-actions]
   [pallet.test-utils :as test-utils])
  (:use
   pallet.crate.chef
   clojure.test))

(deftest chef-test
  (is ; just check for compile errors for now
   (build-actions/build-actions
    {}
    (chef)
    (solo "abc"))))
