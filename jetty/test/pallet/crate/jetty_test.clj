(ns pallet.crate.jetty-test
  (:use
   clojure.test
   pallet.test-utils)
  (:require
   [pallet.build-actions :as build-actions]
   [pallet.crate.jetty :as jetty]))

(deftest invoke-test
  (is (build-actions/build-actions
       {}
       (jetty/jetty)
       (jetty/configure "")
       (jetty/server "")
       (jetty/ssl "")
       (jetty/context "" "")
       (jetty/deploy "" :content "c"))))
