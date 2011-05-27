(ns pallet.crate.zeromq-test
  (:use pallet.crate.zeromq)
  (:require
   [pallet.build-actions :as build-actions])
  (:use clojure.test
        pallet.test-utils)
  (:require
   [pallet.core :as core]
   [pallet.crate.automated-admin-user :as automated-admin-user]
   [pallet.crate.java :as java]
   [pallet.live-test :as live-test]
   [pallet.resource :as resource]
   [pallet.resource.exec-script :as exec-script]
   [pallet.resource.file :as file]
   [pallet.resource.package :as package]
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
       :phases {:bootstrap (resource/phase
                            (package/minimal-packages)
                            (package/package-manager :update)
                            (automated-admin-user/automated-admin-user))
                :configure (resource/phase
                            (java/java :openjdk :jdk)
                            (install)
                            (install-jzmq))
                :verify (resource/phase
                         (exec-script/exec-checked-script
                          "Check jar exists"
                          (file-exists? "/usr/local/share/java/zmq.jar")))}}}
     (core/lift (:zeromq node-types) :phase :verify :compute compute))))
