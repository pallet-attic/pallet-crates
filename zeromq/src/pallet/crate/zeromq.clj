(ns pallet.crate.zeromq
  (:require
   [pallet.action.exec-script :as exec-script]
   [pallet.action.package :as package]
   [pallet.action.remote-directory :as remote-directory]
   [pallet.crate.git :as git]
   [pallet.crate.iptables :as iptables]
   [pallet.crate.java :as java]
   [pallet.crate.maven :as maven]
   [pallet.parameter :as parameter]))

(def src-path "/opt/local/zeromq")
(def md5s {})

(defn download-url
  "The url for downloading zeromq"
  [version]
  (format
   "http://download.zeromq.org/zeromq-%s.tar.gz"
   version))

(defn install
  "Install zeromq from source."
  [session & {:keys [version] :or {version "2.1.7"}}]
  (->
   session
   (package/packages
    :yum ["gcc" "gcc-c++" "glib" "glibc-common" "libuuid-devel"]
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
    ("/sbin/ldconfig"))
   ;;(parameter/assoc-for-target [:zeromq :version] version)
   ))

(defn install-jzmq
  "Install jzmq from source. You must install zeromq first."
  [session & {:keys [version] :or {version "master"}}]
  (->
   session
   (maven/package)
   (git/git)
   (package/packages
    :yum ["libtool" "pkg-config" "autoconf"]
    :aptitude ["libtool" "pkg-config" "autoconf"])

   (exec-script/exec-checked-script
    "Build jzmq"

    (var tmpdir (quoted (make-temp-dir "rf")))
    (cd (quoted @tmpdir))
    (git clone "https://github.com/zeromq/jzmq.git")

    (git checkout ~version)
    (cd "jzmq")
    (export (str "JAVA_HOME=" (~java-home)))

    ("./autogen.sh")
    ("./configure")
    (make)
    (make install) ; install the jni lib
    ;; Install jar to local repo.
    ;; We skip tests as pom isn't set up for configuring java.library.path
    ("mvn" "-q" "install" "-DskipTests=true"))))

(defn iptables-accept
  "Accept zeromq connections, by default on port 5672"
  ([session] (iptables-accept session 5672))
  ([session port]
     (iptables/iptables-accept-port session port)))
