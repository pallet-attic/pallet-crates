(ns pallet.crate.public-dns-if-no-nameserver
  (:require
   [pallet.action.conditional :as conditional]
   [pallet.crate.resolv :as resolv]))

(defonce google-dns ["8.8.8.8" "8.8.4.4"])
(defonce opendns-nameservers ["208.67.222.222" "208.67.220.220"])

(defn public-dns-if-no-nameserver
  "Install a public nameserver if none configured"
  [session & nameservers]
  (let [nameservers (if (seq nameservers)
                      nameservers
                      (conj google-dns (first opendns-nameservers))) ]
    (-> session
        (conditional/when-not
         (nameservers)
         (resolv/resolv nil nameservers :rotate true)))))
