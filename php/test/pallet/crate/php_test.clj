(ns pallet.crate.php-test
  (:use pallet.crate.php
        pallet.test-utils
        clojure.test)
  (:require
   [pallet.build-actions :as build-actions]))

(deftest invoke-test
  (is (build-actions/build-actions
       {}
       (php)
       (php "extension"))))
