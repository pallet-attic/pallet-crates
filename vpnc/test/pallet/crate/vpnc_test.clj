(ns pallet.crate.vpnc-test
  (:use pallet.crate.vpnc)
  (:require
   [pallet.utils :as utils])
  (:use clojure.test
        pallet.test-utils))

(deftest invoke-test
  (is
   (build-resources
    []
    (package)
    (configure-vpn "fred" :content " ")
    (vpn-up "fred")
    (vpn-down))))
