(ns pallet.crate.zeromq
  (:require
<<<<<<< HEAD
   [pallet.resource.exec-script :as exec-script]
   [pallet.resource.package :as package]
   [pallet.resource.remote-directory :as remote-directory]
   [pallet.crate.git :as git]
   [pallet.crate.iptables :as iptables]
   [pallet.crate.java :as java]
   [pallet.crate.maven :as maven]
   [pallet.parameter :as parameter]))
=======
   [pallet.action.exec-script :as exec-script]
   [pallet.action.package :as package]
   [pallet.action.remote-directory :as remote-directory]
   [pallet.crate.iptables :as iptables]))
>>>>>>> develop

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
<<<<<<< HEAD
  [request & {:keys [version] :or {version "2.0.10"}}]
=======
  [session & {:keys [version] :or {version "2.1.7"}}]
>>>>>>> develop
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
  [request & {:keys [version]}]
  (->
   request
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

    (cd "jzmq")
    (export (str "JAVA_HOME="
                 @(dirname @(dirname @(update-alternatives --list javac)))))

    ("./autogen.sh")
    ("./configure")
    (make)
    (make install) ; install the jni lib
    ;; Install jar to local repo.
    ;; We skip tests as pom isn't set up for configuring java.library.path
    ("mvn" "-q" "install" "-Dmaven.test.skip=true"))))

(defn iptables-accept
  "Accept zeromq connections, by default on port 5672"
  ([session] (iptables-accept session 5672))
  ([session port]
     (iptables/iptables-accept-port session port)))
