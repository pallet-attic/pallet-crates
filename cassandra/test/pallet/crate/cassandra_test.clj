(ns pallet.crate.cassandra-test
  (:use pallet.crate.cassandra)
  (:require
   [pallet.action.package :as package]
   [pallet.build-actions :as build-actions]
   [pallet.stevedore :as stevedore])
  (:use clojure.test
        pallet.test-utils))

(deftest cassandra-test
  []
  (let [a {:tag :n :image {:os-family :ubuntu}}]
    (is (first
         (build-actions/build-actions
          {:server a}
          (from-package)
          (install))))))
