(ns pallet.crate.ssh-test
  (:use pallet.crate.ssh)
  (:require
   [pallet.build-actions :as build-actions]
   [pallet.crate.iptables :as iptables]
   [pallet.test-utils :as test-utils])
  (:use clojure.test))

(deftest iptables-accept-test
  []
  (is (= (first
          (build-actions/build-actions
           {:server {:group-name :n :image {:os-family :ubuntu}}}
           (iptables/iptables-accept-port 22 "tcp")))
         (first
          (build-actions/build-actions
           {:server {:group-name :n :image {:os-family :ubuntu}}}
           (iptables-accept))))))

(deftest invoke-test
  (is (build-actions/build-actions
       {:server {:node (test-utils/make-node "tag" :id "id")}}
       (openssh)
       (sshd-config "")
       (iptables-accept)
       (iptables-throttle)
       (nagios-monitor))))
