(ns pallet.crate.zeromq-test
  (:use pallet.crate.zeromq)
  (:require
   [pallet.build-actions :as build-actions])
  (:use clojure.test
        pallet.test-utils)
  (:require
   [pallet.build-actions :as build-actions]
   [pallet.core :as core]
   [pallet.crate.automated-admin-user :as automated-admin-user]
   [pallet.crate.java :as java]
   [pallet.live-test :as live-test]
   [pallet.action :as action]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.file :as file]
   [pallet.action.package :as package]
   [pallet.phase :as phase]
   [pallet.test-utils :as test-utils]))

(deftest invocation
  (is (build-actions/build-actions
       {}
       (install))))


(deftest live-test
  (doseq [image live-test/*images*]
    (live-test/test-nodes
     [compute node-map node-types]
     {:zeromq
      {:image image
       :count 1
       :phases {:bootstrap (phase/phase-fn
                            (package/minimal-packages)
                            (package/package-manager :update)
                            (automated-admin-user/automated-admin-user))
                :configure (phase/phase-fn
                            (java/java :openjdk :jdk)
                            (install)
                            (install-jzmq))
                :verify (phase/phase-fn
                         (exec-script/exec-checked-script
                          "Check jar exists"
                          (test
                           (file-exists? "/usr/local/share/java/zmq.jar"))))}}}
     (core/lift (:zeromq node-types) :phase :verify :compute compute))))
