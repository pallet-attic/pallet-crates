(ns pallet.crate.monit
  "Install monit"
  (:require
   [pallet.action.package :as package]))

(defn package
  "Install monit from system package"
  [session]
  (->
   session
   (package/packages
    :yum ["monit"]
    :aptitude ["monit"])))

(defn monitor
  "Monitor something with monit"
  [session & {:as options}]
  )
