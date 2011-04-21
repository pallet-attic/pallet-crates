(ns pallet.crate.git-test
  (:use pallet.crate.git)
  (:require
   [pallet.action.exec-script :as exec-script]
   [pallet.action.package :as package]
   [pallet.build-actions :as build-actions]
   [pallet.core :as core]
   [pallet.crate.automated-admin-user :as automated-admin-user]
   [pallet.live-test :as live-test]
   [pallet.phase :as phase])
  (:use clojure.test
        pallet.test-utils))

(deftest git-test
  []
  (let [apt-server (target-server :packager :aptitude)]
    (is (= (first
            (build-actions/build-actions
             apt-server
             (package/package-manager :update)
             (package/package "git-core")
             (package/package "git-email")))
           (first
            (build-actions/build-actions
             apt-server
             (git))))))
  (let [yum-server (target-server :packager :yum)]
    (is (= (first
            (build-actions/build-actions
             yum-server
             (package/package-manager :update)
             (package/package "git")
             (package/package "git-email")))
           (first
            (build-actions/build-actions
             yum-server
             (git)))))))

(deftest live-test
  (doseq [image live-test/*images*]
    (live-test/test-nodes
     [compute node-map node-types]
     {:git
      {:image image
       :count 1
       :phases {:bootstrap (phase/phase-fn
                            (package/package-manager :update)
                            (package/package "coreutils") ;; for debian
                            (automated-admin-user/automated-admin-user))
                :configure #'git
                :verify (phase/phase-fn
                         (exec-script/exec-checked-script
                          "check git command found"
                          (git "--version")))}}}
     (core/lift (:git node-types) :phase :verify :compute compute))))
