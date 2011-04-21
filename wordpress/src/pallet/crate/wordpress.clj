(ns pallet.crate.wordpress
  "Install wordpress"
  (:require
   [pallet.action.package :as package]
   [pallet.crate.mysql :as mysql])
  (:use
   pallet.thread-expr))

(defn wordpress
  "Install wordpress - no configuration yet!"
  [session
   mysql-wp-username
   mysql-wp-password
   mysql-wp-database
   & extensions]
  (->
   session
   (package/package "wordpress")
   (for-> [extension extensions]
     (package/package (format "wordpress-%s" (name extension))))
   (mysql/create-database mysql-wp-database)
   (mysql/create-user mysql-wp-username mysql-wp-password)
   (mysql/grant "ALL" (format "`%s`.*" mysql-wp-database) mysql-wp-username)))
