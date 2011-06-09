(ns pallet.crate.nexus-test
  (:require
   [pallet.action.exec-script :as exec-script]
   [pallet.action.package :as package]
   [pallet.build-actions :as build-actions]
   [pallet.core :as core]
   [pallet.crate.automated-admin-user :as automated-admin-user]
   [pallet.crate.java :as java]
   [pallet.crate.network-service :as network-service]
   [pallet.crate.nexus :as nexus]
   [pallet.live-test :as live-test]
   [pallet.phase :as phase]
   [pallet.test-utils :as test-utils]
   [clojure.contrib.logging :as logging])
  (:use clojure.test))

(deftest live-test
  (live-test/test-for
   [image (live-test/images) ]
   (logging/trace (format "nexus live test: image %s" (pr-str image)))
   (live-test/test-nodes
    [compute node-map node-types]
    {:pgtest
     (->
      (core/server-spec
       :phases {:bootstrap (phase/phase-fn
                            (package/minimal-packages)
                            (package/package-manager :update)
                            (automated-admin-user/automated-admin-user))
                :settings (phase/phase-fn
                           (nexus/settings
                            (nexus/settings-map {})))
                :configure (phase/phase-fn
                            (java/java :openjdk :jdk)
                            (nexus/install)
                            (nexus/service :action :enable)
                            (nexus/service :action :start))
                :verify (phase/phase-fn
                         (network-service/wait-for-http-status
                          "http://localhost:8081/nexus"
                          200 :url-name "nexus server")
                         (exec-script/exec-checked-script
                          "check nexus functional"))}
       :count 1
       :node-spec (core/node-spec :image image)))}
    (is
     (core/lift
      (val (first node-types)) :phase [:verify] :compute compute)))))
