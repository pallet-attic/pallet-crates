(ns pallet.crate.cassandra
  (:require
   [pallet.action :as action]
   [pallet.action.directory :as directory]
   [pallet.action.file :as file]
   [pallet.action.package :as package]
   [pallet.action.remote-directory :as remote-directory]
   [pallet.action.remote-file :as remote-file]
   [pallet.action.service :as service]
   [pallet.argument :as argument]
   [pallet.parameter :as parameter]
   [pallet.stevedore :as stevedore]
   [clojure.string :as string]))

(def install-path "/usr/local/cassandra")
(def log-path "/var/log/cassandra")
(def config-path "/etc/cassandra")
(def data-path "/var/cassandra")
(def cassandra-home install-path)
(def cassandra-user "cassandra")
(def cassandra-group "cassandra")

(defn from-package
  [session]
  (-> session
   (package/package-source
    "cassandra"
    :aptitude {:url "ppa:cassandra-ubuntu/stable"})
   (package/package-manager :update)
   (package/package "cassandra")))

(defn url "Download url"
  [version]
  (format
   "http://www.apache.org/dist/cassandra/%s/apache-cassandra-%s-bin.tar.gz"
   version version))

(defn install
  "Install Cassandra"
  [session & {:keys [version user group]
              :or {version "0.6.3"}
              :as options}]
  (let [url (url version)
        owner (or user cassandra-user)
        group (or group cassandra-group)
        home (format "%s-%s" install-path version)]
    (->
     session
     (parameter/parameters
      [:cassandra :home] home
      [:cassandra :owner] owner
      [:cassandra :group] group)
     (remote-directory/remote-directory
      home
      :url url :md5-url (str url ".md5")
      :unpack :tar :tar-options "xz" :owner owner :group group)
     (directory/directory
      log-path :owner owner :group group :mode "0755")
     (directory/directory
      config-path :owner owner :group group :mode "0755")
     (directory/directory
      data-path :owner owner :group group :mode "0755")
     (remote-file/remote-file
      (format "%s/log4j.properties" config-path)
      :remote-file (format "%s/conf/log4j.properties" home)
      :owner owner :group group :mode "0644")
     (remote-file/remote-file
      (format "%s/storage-conf.xml" config-path)
      :remote-file (format "%s/conf/storage-conf.xml" home)
      :owner owner :group group :mode "0644")
     (remote-file/remote-file
      (format "%s/storage-conf.xml" config-path)
      :remote-file (format "%s/cassandra.in.sh" home)
      :owner owner :group group :mode "0644")
     (file/sed
      (format "%s/log4j.properties" config-path)
      {"log4j.rootLogger=INFO, CONSOLE"
       "log4j.rootLogger=INFO, ROLLINGFILE"
       "log4j.appender.ROLLINGFILE.File=cassandra.log"
       (format "log4j.appender.ROLLINGFILE.File=%s/cassandra.log" log-path)}
      :seperator "|"))))
