(ns pallet.crate.zeromq-test
  (:use pallet.crate.zeromq)
  (:require
   [pallet.build-actions :as build-actions])
  (:use clojure.test
        pallet.test-utils))

(deftest invocation
  (is (build-actions/build-actions
       {}
       (install))))
