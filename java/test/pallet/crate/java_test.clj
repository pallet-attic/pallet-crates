(ns pallet.crate.java-test
  (:use pallet.crate.java)
  (:require
   [pallet.action.environment :as environment]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.file :as file]
   [pallet.action.package :as package]
   [pallet.action.package.jpackage :as jpackage]
   [pallet.action.remote-file :as remote-file]
   [pallet.build-actions :as build-actions]
   [pallet.core :as core]
   [pallet.crate.automated-admin-user :as automated-admin-user]
   [pallet.live-test :as live-test]
   [pallet.phase :as phase]
   [pallet.script :as script]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore]
   [pallet.thread-expr :as thread-expr]
   [pallet.utils :as utils]
   [clojure.contrib.logging :as logging])
  (:use clojure.test
        pallet.test-utils))

(use-fixtures :once with-ubuntu-script-template)

(defn pkg-config [session]
  (-> session
      (package/package-manager :universe)
      (package/package-manager :multiverse)
      (package/package-manager :update)))

(def noninteractive
  (script/with-script-context [:ubuntu]
    (stevedore/with-script-language :pallet.stevedore.bash/bash
      (stevedore/script (package-manager-non-interactive)))))

(defn debconf [session pkg]
  (package/package-manager
   session
   :debconf
   (str pkg " shared/present-sun-dlj-v1-1 note")
   (str pkg " shared/accepted-sun-dlj-v1-1 boolean true")))

(deftest java-default-test
  (is (= (first
          (build-actions/build-actions
           {}
           (package/package-source
            "Partner"
            :aptitude {:url ubuntu-partner-url
                       :scopes ["partner"]})
           (pkg-config)
           (package/package-manager :update)
           (debconf "sun-java6-bin")
           (package/package "sun-java6-bin")
           (debconf "sun-java6-jdk")
           (package/package "sun-java6-jdk")
           (environment/system-environment
            "java"
            {"JAVA_HOME" (stevedore/script (~jdk-home))})))
         (first
          (build-actions/build-actions
           {}
           (java))))))

(deftest java-sun-test
  (is (= (first
          (build-actions/build-actions
           {}
           (package/package-source
            "Partner"
            :aptitude {:url ubuntu-partner-url
                       :scopes ["partner"]})
           (pkg-config)
           (package/package-manager :update)
           (debconf "sun-java6-bin")
           (package/package "sun-java6-bin")
           (debconf "sun-java6-jdk")
           (package/package "sun-java6-jdk")
           (environment/system-environment
            "java"
            {"JAVA_HOME" (stevedore/script (~jdk-home))})))
         (first
          (build-actions/build-actions
           {}
           (java :sun :bin :jdk))))))

(deftest java-openjdk-test
  (is (= (first
          (build-actions/build-actions
           {}
           (package/package-manager :update)
           (package/package "openjdk-6-jre")
           (environment/system-environment
            "java"
            {"JAVA_HOME" (stevedore/script (~java-home))})))
         (first
          (build-actions/build-actions
           {}
           (java :openjdk :jre)))))
  (is (= (first
          (build-actions/build-actions
           {:server {:image {} :packager :pacman}}
           (package/package-manager :update)
           (package/package "openjdk6")
           (environment/system-environment
            "java"
            {"JAVA_HOME" (stevedore/script (~java-home))})))
         (first
          (build-actions/build-actions
           {:server {:image {} :packager :pacman}}
           (java :openjdk :jre))))))


(deftest invoke-test
  (is
   (build-actions/build-actions
    {}
    (java :openjdk :jdk)
    (jce-policy-file "f" :content ""))))

(def rh [{:os-family :centos} {:os-family :fedora} {:os-family :rhel}])

(deftest live-test
  (live-test/test-for
   [image (live-test/exclude-images live-test/*images* rh)]
    (live-test/test-nodes
     [compute node-map node-types]
     {:java
      {:image image
       :count 1
       :phases {:bootstrap (phase/phase-fn
                            (package/minimal-packages)
                            (package/package-manager :update)
                            (automated-admin-user/automated-admin-user))
                :configure (phase/phase-fn (java :sun))
                :verify (phase/phase-fn
                         (exec-script/exec-checked-script
                          "check java installed"
                          ("java" -version))
                 (exec-script/exec-checked-script
                  "check java installed under java home"
                  ("test" (file-exists? (str (~java-home) "/bin/java"))))
                 (exec-script/exec-checked-script
                  "check javac installed under jdk home"
                  ("test" (file-exists? (str (~jdk-home) "/bin/javac"))))
                 (exec-script/exec-checked-script
                  "check JAVA_HOME set to jdk home"
                  (source "/etc/environment")
                  ("test" (= (~jdk-home) @JAVA_HOME))))}}}
     (core/lift (val (first node-types)) :phase :verify :compute compute))))

;; To run this test you will need to download the Oracle Java rpm downloads in
;; the artifacts directory.
(deftest centos-live-test
  (live-test/test-for
   [image (live-test/filter-images (live-test/images) rh)]
   (logging/info (format "testing %s" (pr-str image)))
   (live-test/test-nodes
    [compute node-map node-types]
    {:java
     {:image image
      :count 1
      :phases
      {:bootstrap (phase/phase-fn
                   (package/minimal-packages)
                   (package/package-manager :update)
                   (automated-admin-user/automated-admin-user))
       :configure (fn [session]
                    (let [file (if (session/is-64bit? session)
                                    "jdk-6u23-linux-x64-rpm.bin"
                                    "jdk-6u24-linux-i586-rpm.bin")]
                      (->
                       session
                       (rpm-bin-file file :local-file (str "artifacts/" file))
                       (java :sun :rpm-bin (str "./" file)))))
       :verify (phase/phase-fn
                (exec-script/exec-checked-script
                 "check java installed"
                 ("java" -version))
                (exec-script/exec-checked-script
                 "check java installed under java home"
                 ("test" (file-exists? (str (~java-home) "/bin/java"))))
                (exec-script/exec-checked-script
                 "check javac installed under jdk home"
                 ("test" (file-exists? (str (~jdk-home) "/bin/javac"))))
                (exec-script/exec-checked-script
                 "check JAVA_HOME set to jdk home"
                 (source "/etc/profile.d/java.sh")
                 ("test" (= (~jdk-home) @JAVA_HOME))))}}}
    (core/lift (val (first node-types)) :phase :verify :compute compute))))
