(ns server.performance
  (:require
   [pallet.core :as core]
   [pallet.stevedore :as stevedore]
   [pallet.action :as action]
   [pallet.action.directory :as directory]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.package :as package]
   [pallet.crate.iozone :as iozone]
   [pallet.crate.automated-admin-user :as automated-admin-user]))


(defn iozone-test
  [request]
  (->
   request
   (directory/directory "/var/lib/iozone/")
   (exec-script/exec-script
    ("/usr/local/bin/iozone" -Rb
     ~(str "/var/lib/iozone/" (stevedore/script (hostname :s true)) ".xls")
     -s "2g" -i 0 -i 1 -i 2 -f "/mnt/testfile" -r "32k" -g "2G"))))

(core/defnode small
  {}
  :bootstrap (phase/phase-fn
              (package/package-manager :update)
              (automated-admin-user/automated-admin-user))
  :configure (phase/phase-fn
              (iozone/iozone))
  :diskperf  (phase/phase-fn
              (iozone-test)))
