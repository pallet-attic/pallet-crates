(ns pallet.crate.upstart-test
  (:use pallet.crate.upstart
        clojure.test
        pallet.test-utils)
  (:require
   [pallet.build-actions :as build-actions]))

(deftest invoke-test
  (is (build-actions/build-actions
       {}
       (package)
       (job "abc"
            :script "ls"
            :pre-start-exec "ifup -a"
            :limit {"disk" "100 200"}
            :env "HOME=/home"
            :export ["HOME" "AWAY"]
            :respawn true))))
