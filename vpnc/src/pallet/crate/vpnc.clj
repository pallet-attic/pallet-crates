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
   [pallet.action.exec-script :as exec-script]
   [pallet.action.file :as file]
   [pallet.action.package :as package]
   [pallet.action.package.rpmforge :as rpmforge]
   [pallet.action.remote-file :as remote-file]
   [pallet.script.lib :as lib]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore]
   [pallet.thread-expr :as thread-expr]))

(defn- default-config-home
  "Default configuration home for vpnc conf files"
  []
  (stevedore/script (str (~lib/pkg-config-root) "/vpnc")))

(defn- default-sbin
  "Default sbin location"
  []
  (stevedore/script (~lib/pkg-sbin)))

(defn package
 "Install vpnc client from packages"
 [session & {:keys [config-home sbin]}]
 (let [config-home (or config-home (default-config-home))
       sbin (or sbin (default-sbin))]
   (->
    session
    (thread-expr/when-> (= :centos (session/os-family session))
                        (rpmforge/add-rpmforge)
                        (package/package-manager :update))
    (package/package "vpnc")
    (file/file (str config-home "/vpnc-script") :mode "0755")
    (parameter/assoc-for-target
     [:vpnc :config-home] config-home
     [:vpnc :sbin] sbin))))

(defn- config-for-name
  "Path for given vpn-name config"
  [session vpn-name]
  (str
   (parameter/get-for-target session [:vpnc :config-home] (default-config-home))
   "/" vpn-name ".conf"))

(defn configure-vpn
  "Configure a vpn. Options as for `remote-file/remote-file`.

   `pallet.config-file.format/name-values` could be used to generate the
   configuration based on a map, and passed to this function via :content.

       (configure-vpn session \"myvpn\"
         :content (name-values { \"IPSec gateway\" \"<gateway>\"
                                 \"IPSec ID\" \"<group-id>\"
                                 \"IPSec secret\" \"<group-psk>\"
                                 \"IKE Authmode\" \"hybrid\"
                                 \"Xauth username\" \"<username>\"
                                 \"Xauth password\" \"<password>\"}))"
  [session name & {:as options}]
  (->
   session
   (thread-expr/apply-map->
    remote-file/remote-file
    (config-for-name session name)
    :mode "0600" options)))

(defn vpn-up
  "Bring the vpn up"
  [session name]
  (let [sbin (parameter/get-for-target session [:vpnc :sbin] (default-sbin))]
    (->
     session
     (exec-script/exec-checked-script
      (format "Bring VPN %s up" name)
      (~(str sbin "/vpnc") ~name)))))

(defn vpn-down
  "Bring the vpn down"
  [session]
  (let [sbin (parameter/get-for-target session [:vpnc :sbin] (default-sbin))]
    (->
     session
     (exec-script/exec-checked-script
      "Bring VPN down"
      (~(str sbin "/vpnc-disconnect"))
      ("killall vpnc")))))
