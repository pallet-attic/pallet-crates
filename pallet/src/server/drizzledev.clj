(ns server.drizzledev
  (:require
   [pallet.action :as action]
   [pallet.action.package :as package]
   [pallet.action.service :as service]
   [pallet.action.user :as user]
   [pallet.core :as core]
   [pallet.crate.automated-admin-user :as automated-admin-user]
   [pallet.crate.bzr :as bzr]
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]))

(defn drizzledev-config
  [session]
  (->
   session
   (user/user "testuser" :create-home true :shell :bash)
   (package/package-source
    "drizzle-developers"
    :aptitude {:url "ppa:drizzle-developers/ppa"}
    :yum { :url (str
                 "http://5dollarwhitebox.org/repos/drizzle"
                 (stevedore/script (~lib/arch)))})
   (package/package-manager :update)
   (bzr/bzr)
   (package/package "drizzle-dev")))



(core/defnode drizzledev
  {}
  :bootstrap (phase/phase-fn
              (automated-admin-user/automated-admin-user)
              (package/package-manager :update))
  :configure (phase/phase-fn
              (drizzledev-config)))
