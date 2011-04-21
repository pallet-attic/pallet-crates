(ns pallet.crate.maven
  (:require
   [pallet.session :as session]
   [pallet.action :as action]
   [pallet.action.package :as package]
   [pallet.action.package.jpackage :as jpackage]
   [pallet.action.remote-directory :as remote-directory])
  (:use
   pallet.thread-expr))

(def maven-parameters
 {:maven-home "/opt/maven2"
  :version "3.0.3"})

(defn maven-download-md5
  [version]
  {"2.2.1" "c581a15cb0001d9b771ad6df7c8156f8"
   "3.0.3" "507828d328eb3735103c0492443ef0f0"})

(defn maven-download-url
  [version]
  (str "http://mirrors.ibiblio.org/pub/mirrors/apache/"
       "maven/binaries/apache-maven-" version "-bin.tar.gz"))

(defn download
  [session & {:keys [maven-home version]
              :or {maven-home "/opt/maven2" version "3.0.3"}
              :as options}]
  (remote-directory/remote-directory
   session
   maven-home
   :url (maven-download-url version)
   :md5 (maven-download-md5 version)
   :unpack :tar :tar-options "xz"))


(defn package
  [session & {:keys [package-name] :or {package-name "maven2"} :as options}]
  (let [use-jpackage (or
                      (= :amzn-linux (session/os-family session))
                      (and
                       (= :centos (session/os-family session))
                       (re-matches
                        #"5\.[0-5]" (session/os-version session))))
        options (if use-jpackage
                  (assoc options
                    :enable ["jpackage-generic" "jpackage-generic-updates"])
                  options)]
    (->
     session
     (when-> use-jpackage
      (jpackage/add-jpackage :releasever "5.0")
      (jpackage/package-manager-update-jpackage)
      (jpackage/jpackage-utils))
     (apply-map-> package/package package-name options))))
