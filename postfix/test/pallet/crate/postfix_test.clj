(ns pallet.crate.postfix-test
  (:use pallet.crate.postfix
        pallet.test-utils
        clojure.test)
  (:require
   [pallet.build-actions :as build-actions]))

(deftest invoke-test
  (is (build-actions/build-actions
       {}
       (postfix "a.com" :internet-site))))
