(ns pallet.crate.git-test
  (:use pallet.crate.git)
  (:require
   [pallet.core :as core]
   [pallet.resource :as resource]
   [pallet.resource.package :as package]
   [pallet.resource.exec-script :as exec-script]
   [pallet.crate.automated-admin-user :as automated-admin-user]
   [pallet.live-test :as live-test])
  (:use clojure.test
        pallet.test-utils))

(deftest git-test
  []
  (let [a {:tag :n :image {:packager :aptitude}}]
    (is (= (first
            (build-resources
             [:node-type a]
             (package/package-manager :update)
             (package/package "git-core")
             (package/package "git-email")))
           (first
            (build-resources
             [:node-type a]
             (git))))))
  (let [a {:tag :n :image {:packager :yum}}]
    (is (= (first
            (build-resources
             [:node-type a]
             (package/package-manager :update)
             (package/package "git")
             (package/package "git-email")))
           (first
            (build-resources
             [:node-type a]
             (git)))))))

(deftest live-test
  (doseq [image live-test/*images*]
    (live-test/test-nodes
     [compute node-map node-types]
     {:git
      {:image image
       :count 1
       :phases {:bootstrap (resource/phase
                            (package/package-manager :update)
                            (package/package "coreutils") ;; for debian
                            (automated-admin-user/automated-admin-user))
                :configure #'git
                :verify (resource/phase
                         (exec-script/exec-checked-script
                          "check git command found"
                          (git "--version")))}}}
     (core/lift (:git node-types) :phase :verify :compute compute))))
