(ns pallet.crate.zookeeper-test
  (:use pallet.crate.zookeeper)
  (:use clojure.test)
  (:require
   [pallet.resource :as resource]
   [pallet.test-utils :as test-utils]
   [pallet.compute :as compute]
   [pallet.core :as core]
   [pallet.crate.automated-admin-user :as automated-admin-user]
   [pallet.crate.java :as java]
   [pallet.live-test :as live-test]
   [pallet.parameter :as parameter]
   [pallet.request-map :as request-map]
   [pallet.resource :as resource]
   [pallet.resource.exec-script :as exec-script]
   [pallet.resource.network-service :as network-service]
   [pallet.resource.package :as package]
   [pallet.resource.service :as service]))

(deftest zookeeper-test
  (is ; just check for compile errors for now
   (test-utils/build-resources
    [:target-node (test-utils/make-node "tag")
     :node-type {:tag "tag" :image {:os-family :ubuntu}}]
    (install)
    (configure)
    (init))))


(deftest live-test
  (doseq [image live-test/*images*]
    (live-test/test-nodes
     [compute node-map node-types]
     {:zookeeper
      {:image image
       :count 1
       :phases {:bootstrap (resource/phase
                            (package/minimal-packages)
                            (package/package-manager :update)
                            (automated-admin-user/automated-admin-user))
                :configure (resource/phase
                            (java/java :openjdk :jdk)
                            (install)
                            (configure)
                            (init)
                            (config-files)
                            (pallet.resource.service/service
                             "zookeeper" :action :restart))
                :verify (resource/phase
                         (network-service/wait-for-port-listen 2181)
                         (exec-script/exec-checked-script
                          "check zookeeper"
                          (println "zookeeper ruok")
                          (pipe (println "ruok") ("nc" "localhost" 2181))
                          (println "zookeeper stat ")
                          (pipe (println "stat") ("nc" "localhost" 2181))
                          (println "zookeeper dump ")
                          (pipe (println "dump") ("nc" "localhost" 2181))
                          (test (= "imok"
                                   @(pipe (println "ruok")
                                          ("nc" "localhost" 2181))))))}}}
     (core/lift (:zookeeper node-types) :phase :verify :compute compute))))
