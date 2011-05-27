(ns pallet.crate.bzr-test
  (:use pallet.crate.bzr)
  (:require
   [pallet.build-actions :as build-actions]
   [pallet.action.package :as package]
   [pallet.stevedore :as stevedore])
  (:use clojure.test
        pallet.test-utils))

(deftest bzr-test
  []
  (is (= (first
          (build-actions/build-actions
           {}
           (package/package "bzr")
           (package/package "bzrtools")))
         (first
          (build-actions/build-actions
           {}
           (bzr))))))
