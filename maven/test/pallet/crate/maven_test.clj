(ns pallet.crate.maven-test
  (:use
   pallet.crate.maven
   clojure.test
   pallet.test-utils)
  (:require
   [pallet.build-actions :as build-actions]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.remote-directory :as remote-directory]
   [pallet.core :as core]
   [pallet.crate.automated-admin-user :as automated-admin-user]
   [pallet.live-test :as live-test]
   [pallet.phase :as phase]))

(deftest download-test
  (is (= (first
          (build-actions/build-actions
           {}
           (remote-directory/remote-directory
            "/opt/maven2"
            :url (maven-download-url "2.2.1")
            :md5 (maven-download-md5 "2.2.1")
            :unpack :tar :tar-options "xj"))))
      (first
       (build-actions/build-actions
        {}
        (download :version "2.2.1")))))

(deftest live-test
  (live-test/test-for
   [image live-test/*images*]
   (live-test/test-nodes
    [compute node-map node-types]
    {:maven
     {:image image
      :count 1
      :phases {:bootstrap (phase/phase-fn
                           (automated-admin-user/automated-admin-user))
               :configure (phase/phase-fn
                           (package))
               :verify (phase/phase-fn
                        (exec-script/exec-checked-script
                         "check mvn command exists"
                         (mvn -version)))}}}
    (core/lift (:maven node-types) :phase :verify :compute compute))))
