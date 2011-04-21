(ns pallet.crate.zeromq
  (:require
   [pallet.action.exec-script :as exec-script]
   [pallet.action.package :as package]
   [pallet.action.remote-directory :as remote-directory]
   [pallet.crate.iptables :as iptables]))

(def src-path "/opt/local/zeromq")
(def md5s {})

(defn download-url
  "The url for downloading zeromq"
  [version]
  (format
   "http://www.zeromq.org/local--files/area:download/zeromq-%s.tar.gz"
   version))

(defn install
  "Install zeromq from source."
  [session & {:keys [version] :or {version "2.0.9"}}]
  (->
   session
   (package/packages
    :yum ["gcc" "glib" "glibc-common" "uuid-dev"]
    :aptitude ["build-essential" "uuid-dev"])
   (remote-directory/remote-directory
    src-path
    :url (download-url version) :md5 (md5s version) :unpack :tar)
   (exec-script/exec-checked-script
    "Build zeromq"
    (cd ~src-path)
    ("./configure")
    (make)
    (make install)
    (ldconfig))))

(defn iptables-accept
  "Accept zeromq connections, by default on port 5672"
  ([session] (iptables-accept session 5672))
  ([session port]
     (iptables/iptables-accept-port session port)))
