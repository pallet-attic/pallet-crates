(ns pallet.crate.cloudkick-test
  (:use pallet.crate.cloudkick)
  (:use clojure.test)
  (:require
   [pallet.action.package :as package]
   [pallet.action.remote-file :as remote-file]
   [pallet.build-actions :as build-actions]
   [pallet.core :as core]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [pallet.test-utils :as test-utils]))

(deftest cloudkick-test
  (let [a (test-utils/make-node "a")
        session {:server {:node a}}]
    (is (= (first
            (build-actions/build-actions
             session
             (package/package-source
              "cloudkick"
              :aptitude
              {:url "http://packages.cloudkick.com/ubuntu"
               :key-url "http://packages.cloudkick.com/cloudkick.packages.key"}
              :yum { :url (str "http://packages.cloudkick.com/redhat/"
                               (stevedore/script (~lib/arch)))})
             (package/package-manager :update)
             (remote-file/remote-file
              "/etc/cloudkick.conf"
              :content
              "oauth_key key\noauth_secret secret\ntags any\nname node\n\n\n\n")
             (package/package "cloudkick-agent")))
           (first
            (build-actions/build-actions
             session
             (cloudkick "node" "key" "secret")))))))
