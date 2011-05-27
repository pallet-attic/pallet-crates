(ns pallet.crate.vpnc-test
  (:use pallet.crate.vpnc)
  (:require
   [pallet.build-actions :as build-actions]
   [pallet.utils :as utils])
  (:use clojure.test
        pallet.test-utils))

(deftest invoke-test
  (is
   (build-actions/build-actions
    {}
    (package)
    (configure-vpn "fred" :content " ")
    (vpn-up "fred")
    (vpn-down))))
