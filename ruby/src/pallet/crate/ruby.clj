(ns pallet.crate.ruby
  "Installation of ruby from source"
  (:require
   [pallet.action :as action]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.file :as file]
   [pallet.action.package :as package]
   [pallet.action.remote-file :as remote-file]
   [pallet.session :as session]
   [pallet.script :as script]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [pallet.thread-expr :as thread-expr]
   [clojure.string :as string]))

(script/defscript ruby-version [])
(script/defimpl ruby-version :default []
  (pipe ("ruby" "--version")
        (~lib/cut "" :fields 2 :delimiter " ")))

(def src-packages
  {:aptitude ["zlib-devel"  "gcc" "gcc-c++" "make"
              "curl-devel" "expat-devel" "gettext-devel"
              "libncurses5-dev" "libreadline-dev"
              ;; ubuntu 10.04
              "zlib1g" "zlib1g-dev" "zlibc"
              "libssl-dev"]
   :yum ["openssl-devel" "zlib-devel" "gcc" "make"
         "curl-devel" "expat-devel" "gettext-devel" "readline-devel"
         "ncurses-devel"]})

(def version-regex
     {"1.8.7-p72" #"1.8.7.*patchlevel 72"})

(def version-md5
     {"1.8.7-p72" "5e5b7189674b3a7f69401284f6a7a36d"
      "1.8.7-p299" "43533980ee0ea57381040d4135cf9677"
      "1.8.7-p330" "50a49edb787211598d08e756e733e42e"})

(defn ftp-path [tarfile]
  (cond
   (.contains tarfile "1.8") (str "ftp://ftp.ruby-lang.org/pub/ruby/1.8/" tarfile)
   (.contains tarfile "stable") (str "ftp://ftp.ruby-lang.org/pub/ruby/" tarfile)
   :else (str "ftp://ftp.ruby-lang.org/pub/ruby/1.9/" tarfile)))

(defn ruby
  "Install ruby from source"
  ([request] (ruby request "1.8.7-p72"))
  ([request version]
     (let [basename (str "ruby-" version)
           tarfile (str basename ".tar.gz")
           tarpath (str (stevedore/script (~lib/tmp-dir)) "/" tarfile)]
       (->
        request
        (thread-expr/for-> [p (src-packages (session/packager request))]
          (package/package p))
        (remote-file/remote-file
         tarpath :url (ftp-path tarfile) :md5 (version-md5 version))
        (exec-script/exec-script
         (if-not (pipe ("ruby" "--version")
                       (grep (quoted ~(string/replace version "-p" ".*"))))
           (do
             ~(stevedore/checked-script
               "Building ruby"
               ("cd" (~lib/tmp-dir))
               ("tar" xfz ~tarfile)
               ("cd" ~basename)
               ("./configure" "--enable-shared" "--enable-pthread")
               ("make")
               ("make" install)
               (if-not (|| (file-exists? "/usr/bin/ruby")
                           (file-exists? "/usr/local/bin/ruby"))
                 (do (println "Could not find ruby executable")
                     (exit 1)))
               ("cd" "ext/zlib")
               ("ruby" "extconf.rb" "--with-zlib")
               ("make")
               ("make" install)
               ("cd" "../../")
               ("cd" "ext/openssl")
               ("ruby" "extconf.rb")
               ("make")
               ("make" install)
               ("cd" "../../")
               ("cd" "ext/readline")
               ("ruby" "extconf.rb")
               ("make")
               ("make" install)
               ("cd" "../../")
               ("make")
               ("make" install)))))))))

(defn ruby-packages
  "Install ruby from packages"
  [request]
  (package/packages
   request
   :aptitude
   ["ruby" "ruby-dev" "rdoc" "ri" "irb" "libopenssl-ruby" "libzlib-ruby"]
   :yum
   ["ruby" "ruby-devel" "ruby-docs" "ruby-ri" "ruby-rdoc" "ruby-irb"]))
