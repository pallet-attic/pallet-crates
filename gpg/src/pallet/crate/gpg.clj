(ns pallet.crate.gpg
  "Install gpg"
  (:require
   [pallet.action.directory :as directory]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.package :as package]
   [pallet.action.remote-file :as remote-file]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore])
  (:use
   pallet.thread-expr))

(defn gpg
  "Install from packages"
  [session]
  (package/package session "pgpgpg"))

(defn import-key
  "Import key. Content options are as for remote-file."
  [session & {:keys [user] :as options}]
  (let [path "gpg-key-import"
        user (or user (-> session :user :username))
        home (stevedore/script (~lib/user-home ~user))
        dir (str home "/.gnupg")]
    (->
     session
     (directory/directory dir :mode "0700" :owner user)
     (apply->
      remote-file/remote-file
      path (apply concat (merge {:mode "0600" :owner user} options)))
     (exec-script/exec-checked-script
      "Import gpg key"
      (sudo -u ~user gpg -v -v "--homedir" ~dir "--import" ~path))
     (remote-file/remote-file path :action :delete :force true))))
