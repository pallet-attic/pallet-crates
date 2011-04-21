(ns pallet.crate.splunk-test
  (:use pallet.crate.splunk
        clojure.test)
  (:require
   [pallet.build-actions :as build-actions]
   [pallet.test-utils :as test-utils]))

(deftest invoke-test
  (is (build-actions/build-actions
       {:server {:node (test-utils/make-node "tag" :id "id")}}
       (splunk)
       (configure))))
