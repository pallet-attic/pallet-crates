(ns pallet.crate.chef
 "Installation of chef"
  (:require
   [pallet.action.directory :as directory]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.package :as package]
   [pallet.crate.rubygems :as rubygems]
   [pallet.parameter :as parameter]
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils]))

(defn chef
  "Install chef"
  ([session] (chef session "/var/lib/chef"))
  ([session cookbook-dir]
     (->
      session
      (package/package "rsync")
      (rubygems/rubygems)
      (rubygems/gem-source "http://rubygems.org/")
      (rubygems/gem "chef")
      (directory/directory cookbook-dir :owner (:username utils/*admin-user*))
      (parameter/assoc-for-target [:chef :cookbook-dir] cookbook-dir))))

(defn solo
  "Run chef solo"
  [session command]
  (let [cookbook-dir (parameter/get-for-target session [:chef :cookbook-dir])]
    (->
     session
     (exec-script/exec-checked-script
      "Chef solo"
      (chef-solo
       -c ~(str cookbook-dir "/config/solo.rb")
       -j ~(str cookbook-dir "/config/" command ".json"))))))
