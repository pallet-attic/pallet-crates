(ns pallet.crate.sphinx-test
  (:use pallet.crate.sphinx
        clojure.test
        pallet.test-utils)
  (:require
   [pallet.build-actions :as build-actions]))

(deftest invoke-test
  (is (build-actions/build-actions
       {}
       (sphinx))))
