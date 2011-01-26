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
             (package/package "git")
             (package/package "git-email")))
           (first
            (build-resources
             [:node-type a]
             (git)))))))

(deftest live-test
  (doseq [image [{:os-family :ubuntu :os-version-matches "10.04"}
                 {:os-family :ubuntu :os-version-matches "10.10"}
                 {:os-family :debian :os-version-matches "5.0.7"}]]
    (live-test/test-nodes
     [compute node-map node-types]
     {:git
      {:image image
       :count 1
       :phases {:bootstrap (resource/phase
                            (package/package-manager :update)
                            (automated-admin-user/automated-admin-user))
                :configure #'git
                :verify (resource/phase
                         (exec-script/exec-checked-script
                          "check git command found"
                          (git "--help")))}}}
     (core/lift (:git node-types) :phase :verify :compute compute))))
