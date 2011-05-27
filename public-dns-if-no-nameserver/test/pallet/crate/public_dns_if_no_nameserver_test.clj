(ns pallet.crate.public-dns-if-no-nameserver-test
  (:use pallet.crate.public-dns-if-no-nameserver
        pallet.test-utils
        clojure.test)
  (:require
   [pallet.build-actions :as build-actions]))

(deftest invoke-test
  (is (build-actions/build-actions
       {}
       (public-dns-if-no-nameserver))))
