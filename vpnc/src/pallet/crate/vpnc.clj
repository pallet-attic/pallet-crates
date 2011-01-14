(ns pallet.crate.vpnc
  "Crate for vpnc installation and configuration.

   `package` installs from package.  For centos this enables rpmforge.
   `configure-vpn` writes a configuration to a named configuraiton file.
   `vpn-up` to bring up a vpn
   `vpn-down` to bring down a vpn

   ** Links
    - http://www.gentoo.org/doc/en/vpnc-howto.xml
    - http://www.debuntu.org/how-to-connect-to-a-cisco-vpn-using-vpnc"
  (:require
   [pallet.parameter :as parameter]
   [pallet.resource.exec-script :as exec-script]
   [pallet.resource.file :as file]
   [pallet.resource.filesystem-layout :as filesystem-layout]
   [pallet.resource.package :as package]
   [pallet.resource.remote-file :as remote-file]
   [pallet.request-map :as request-map]
   [pallet.stevedore :as stevedore]
   [pallet.thread-expr :as thread-expr]))

(defn- default-config-home
  "Default configuration home for vpnc conf files"
  []
  (stevedore/script (str (pkg-config-root) "/vpnc")))

(defn- default-sbin
  "Default sbin location"
  []
  (stevedore/script (pkg-sbin)))

(defn package
 "Install vpnc client from packages"
 [request & {:keys [config-home sbin]}]
 (let [config-home (or config-home (default-config-home))
       sbin (or sbin (default-sbin))]
   (->
    request
    (thread-expr/when-> (= :centos (request-map/os-family request))
                        (package/add-rpmforge)
                        (package/package-manager :update))
    (package/package "vpnc")
    (file/file (str config-home "/vpnc-script") :mode "0755")
    (parameter/assoc-for-target
     [:vpnc :config-home] config-home
     [:vpnc :sbin] sbin))))

(defn- config-for-name
  "Path for given vpn-name config"
  [request vpn-name]
  (str
   (parameter/get-for-target request [:vpnc :config-home] (default-config-home))
   "/" vpn-name ".conf"))

(defn configure-vpn
  "Configure a vpn. Options as for `remote-file/remote-file`.

   `pallet.resource.format/name-values` could be used to generate the
   configuration based on a map, and passed to this function via :content.

       (configure-vpn request \"myvpn\"
         :content (name-values { \"IPSec gateway\" \"<gateway>\"
                                 \"IPSec ID\" \"<group-id>\"
                                 \"IPSec secret\" \"<group-psk>\"
                                 \"IKE Authmode\" \"hybrid\"
                                 \"Xauth username\" \"<username>\"
                                 \"Xauth password\" \"<password>\"}))"
  [request name & {:as options}]
  (->
   request
   (thread-expr/apply-map->
    remote-file/remote-file
    (config-for-name request name)
    :mode "0600" options)))

(defn vpn-up
  "Bring the vpn up"
  [request name]
  (let [sbin (parameter/get-for-target request [:vpnc :sbin] (default-sbin))]
    (->
     request
     (exec-script/exec-checked-script
      (format "Bring VPN %s up" name)
      (~(str sbin "/vpnc") ~name)))))

(defn vpn-down
  "Bring the vpn down"
  [request]
  (let [sbin (parameter/get-for-target request [:vpnc :sbin] (default-sbin))]
    (->
     request
     (exec-script/exec-checked-script
      "Bring VPN down"
      (~(str sbin "/vpnc-disconnect"))
      ("killall vpnc")))))
