(ns pallet.crate.bzr
  (:require
   [pallet.action.package :as package]))

(defn bzr
  "Install bzr"
  [session]
  (-> session
      (package/package "bzr")
      (package/package "bzrtools")))
