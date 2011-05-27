(ns pallet.crate.squid-test
  (:use
   clojure.test
   pallet.test-utils)
  (:require
   [pallet.action :as action]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.package :as package]
   [pallet.compute :as compute]
   [pallet.core :as core]
   [pallet.crate.automated-admin-user :as automated-admin-user]
   [pallet.crate.network-service :as network-service]
   [pallet.crate.squid :as squid]
   [pallet.live-test :as live-test]
   [pallet.parameter :as parameter]
   [pallet.phase :as phase]))


(def default-config
  "acl SSL_ports port 443\nacl manager proto cache_object\nacl QUERY urlpath_regex cgi-bin \\?\nacl localhost src 127.0.0.1/255.255.255.255\nacl safe_ports port 80\nacl safe_ports port 21\nacl safe_ports port 443\nacl safe_ports port 70\nacl safe_ports port 210\nacl safe_ports port 1025-65535\nacl safe_ports port 280\nacl safe_ports port 488\nacl safe_ports port 591\nacl safe_ports port 777\nacl localnet src 10.0.0.0/8\nacl localnet src 172.16.0.0/12\nacl localnet src 192.168.0.0/16\nacl localnet src fc00::/7\nacl localnet src fe80::/10\nacl all src 0.0.0.0/0.0.0.0\nacl CONNECT method CONNECT\nacl apache rep_header Server ^Apache\nacl to_localhost dst 127.0.0.0/8\nhttp_access allow manager localhost\nhttp_access deny manager\nhttp_access deny !safe_ports\nhttp_access deny CONNECT !SSL_ports\nhttp_access allow localhost\nhttp_access allow localnet\nhttp_access deny all\nicp_access allow all\nrefresh_pattern ^ftp: 1440    20%     10080\nrefresh_pattern ^gopher: 1440    0%      1440\nrefresh_pattern . 0       20%     4320\ncoredump_dir /var/spool/squid\nhttp_port 3128\nbroken_vary_encoding allow apache\nhierarchy_stoplist cgi-bin\nhierarchy_stoplist ?\naccess_log /var/log/squid/access.log squid\ncache deny QUERY\ncache allow all")

(deftest squid-conf-test
  (is (= default-config (squid/squid-conf squid/default-config))))

(def unsupported
  [{:os-family :ubuntu :os-version-matches "10.10"}]) ; no init script

(deftest live-test
  (doseq [image (live-test/exclude-images live-test/*images* unsupported)]
    (live-test/test-nodes
     [compute node-map node-types]
     {:squid
      {:image image
       :count 1
       :phases {:bootstrap (phase/phase-fn
                            (package/minimal-packages)
                            (package/package-manager :update)
                            (automated-admin-user/automated-admin-user))
                :configure (phase/phase-fn
                            (squid/squid)
                            (squid/squid-conf-file
                             :content (squid/squid-conf squid/default-config)
                             :literal true)
                            (squid/init-service
                             :action :restart :if-config-changed true))
                :verify (phase/phase-fn
                         (network-service/wait-for-port-listen
                          3128 :max-retries 1)
                         (exec-script/exec-checked-script
                          "check download via proxy"
                          (download-file
                           "http://www.squid-cache.org/" (make-temp-file "dl")
                           :proxy "http://localhost:3128")))}}}
     (core/lift (:squid node-types) :phase :verify :compute compute))))
