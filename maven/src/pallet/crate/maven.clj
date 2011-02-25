(ns pallet.crate.maven
  (:require
   [pallet.session :as session]
   [pallet.action :as action]
   [pallet.action.package :as package]
   [pallet.action.remote-directory :as remote-directory])
  (:use
   pallet.thread-expr))

(def maven-parameters
 {:maven-home "/opt/maven2"
  :version "2.2.2"})

(defn maven-download-md5
  [version]
  {"2.2.1" "c581a15cb0001d9b771ad6df7c8156f8"})

(defn maven-download-url
  [version]
  (str "http://mirrors.ibiblio.org/pub/mirrors/apache/maven/binaries/apache-maven-"
       version "-bin.tar.bz2"))

(defn download
  [session & {:keys [maven-home version]
              :or {maven-home "/opt/maven2" version "2.2.2"}
              :as options}]
  (remote-directory/remote-directory
   session
   maven-home
   :url (maven-download-url version)
   :md5 (maven-download-md5 version)
   :unpack :tar :tar-options "xj"))

(defn package
  [session]
  (->
   session
   (when->
    (= :amzn-linux (session/os-family session))
    (package/add-jpackage :releasever "5.0"))
   (package/package "maven2")))
