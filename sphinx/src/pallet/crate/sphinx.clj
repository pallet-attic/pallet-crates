(ns pallet.crate.sphinx
  (:require
   [pallet.stevedore :as stevedore]
   [pallet.action.remote-file :as remote-file]
   [pallet.action.exec-script :as exec-script]
   [pallet.script.lib :as lib]))

(def sphinx-downloads
  {"0.9.9" ["http://sphinxsearch.com/downloads/sphinx-0.9.9.tar.gz" "x"]})

(defn sphinx
  "Install sphinx from source"
  ([session] (sphinx session "0.9.9"))
  ([session version]
     (let [info (sphinx-downloads version)
           basename (str "sphinx-" version)
           tarfile (str basename ".tar.gz")
           tarpath (str (stevedore/script (~lib/tmp-dir)) "/" tarfile)]
       (->
        session
        (remote-file/remote-file tarpath :url (first info) :md5 (second info))
        (exec-script/exec-checked-script
         "Sphinx"
         (cd (tmp-dir))
         (tar xfz ~tarfile)
         (cd ~basename)
         ("(" "./configure" "&&" "make" "&&" "make install" ")"
          "||" exit 1) )))))
