(ns pallet.crate.git
  "Crate to install git."
  (:require
   [pallet.session :as session]
   [pallet.action.package :as package]
   [pallet.action.package.epel :as epel]
   [pallet.thread-expr :as thread-expr]))

(defn git
  "Install git"
  [session]
  (->
   session
   (thread-expr/when->
    (#{:amzn-linux :centos} (session/os-family session))
    (epel/add-epel :version "5-4"))
   (package/package-manager :update)
   (package/packages
    :yum ["git" "git-email"]
    :aptitude ["git-core" "git-email"]
    :pacman ["git"])))
