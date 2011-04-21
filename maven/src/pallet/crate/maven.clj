(ns pallet.crate.maven
  (:require
   [pallet.request-map :as request-map]
   [pallet.resource :as resource]
   [pallet.resource.package :as package]
   [pallet.resource.remote-directory :as remote-directory])
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
  (str
   "http://mirrors.ibiblio.org/pub/mirrors/apache/maven/binaries/apache-maven-"
   version "-bin.tar.gz"))

(defn download
  [request & {:keys [maven-home version]
              :or {maven-home "/opt/maven2" version "3.0.3"}
              :as options}]
  (remote-directory/remote-directory
   request
   maven-home
   :url (maven-download-url version)
   :md5 (maven-download-md5 version)
   :unpack :tar :tar-options "xz"))


(defn package
  [request & {:keys [package-name] :or {package-name "maven2"} :as options}]
  (let [use-jpackage (or
                      (= :amzn-linux (request-map/os-family request))
                      (and
                       (= :centos (request-map/os-family request))
                       (re-matches
                        #"5\.[0-5]" (request-map/os-version request))))
        options (if use-jpackage
                  (assoc options
                    :enable ["jpackage-generic" "jpackage-generic-updates"])
                  options)]
    (->
     request
     (when-> use-jpackage
      (package/add-jpackage :releasever "5.0")
      (package/package-manager-update-jpackage)
      (package/jpackage-utils))
     (apply-map-> package/package package-name options))))
