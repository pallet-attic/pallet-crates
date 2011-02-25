(ns pallet.crate.iozone-test
  (:use pallet.crate.iozone)
  (:require
   [pallet.build-actions :as build-actions])
  (:use
   clojure.test))

(deftest invoke-test
  (is
   (build-actions/build-actions
    {}
    (iozone))))
