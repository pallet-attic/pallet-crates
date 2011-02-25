(ns pallet.crate.ruby-test
  (:use pallet.crate.ruby
        clojure.test
        pallet.test-utils)
  (:require
   [pallet.build-actions :as build-actions]
   [pallet.action.exec-script :as exec-script]))

(deftest invoke-test
  (is (build-actions/build-actions
       {}
       (ruby)
       (exec-script/exec-script
        @(ruby-version))
       (ruby-packages))))
