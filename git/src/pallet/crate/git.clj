(ns pallet.crate.git
  "Crate to install git."
  (:require
   [pallet.request-map :as request-map]
   [pallet.resource.package :as package]
   [pallet.thread-expr :as thread-expr]))

(defn git
  "Install git"
  [request]
  (->
   request
   (thread-expr/when->
    (#{:amzn-linux :centos} (request-map/os-family request))
    (package/add-epel :version "5-4"))
   (package/package-manager :update)
   (package/packages
    :yum ["git" "git-email"]
    :aptitude ["git-core" "git-email"]
    :pacman ["git"])))
