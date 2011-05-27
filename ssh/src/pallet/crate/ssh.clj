(ns pallet.crate.ssh
  "Crate for managing ssh"
  (:require
   [pallet.argument :as argument]
   [pallet.session :as session]
   [pallet.crate.iptables :as iptables]
   [pallet.action.package :as package]
   [pallet.action.remote-file :as remote-file]
   [pallet.action.service :as service]
   [pallet.crate.nagios-config :as nagios-config]))

(defn openssh
  "Install OpenSSH"
  [request]
  (package/packages
   request
   :yum ["openssh-clients" "openssh"]
   :aptitude ["openssh-client" "openssh-server"]
   :pacman ["openssh"]))

(defn service-name
  "SSH service name"
  [packager]
  (condp = packager
      :aptitude "ssh"
      :yum "sshd"))

(defn sshd-config
  "Take an sshd config string, and write to sshd_conf."
  [request config]
  (->
   request
   (remote-file/remote-file
    "/etc/ssh/sshd_config"
    :mode "0644"
    :owner "root"
    :content config)
   (service/service
    (service-name (session/packager request))
    :action :reload)))


(defn iptables-accept
  "Accept ssh, by default on port 22"
  ([request] (iptables-accept request 22))
  ([request port]
     (iptables/iptables-accept-port request port)))

(defn iptables-throttle
  "Throttle ssh connection attempts, by default on port 22"
  ([request] (iptables-throttle request 22))
  ([request port] (iptables-throttle request port 60 6))
  ([request port time-period hitcount]
     (iptables/iptables-throttle
      request
      "SSH_CHECK" port "tcp" time-period hitcount)))

(defn nagios-monitor
  "Configure nagios monitoring for ssh"
  [request & {:keys [command] :as options}]
  (nagios-config/service
   request
   (merge
    {:servicegroups [:ssh-services]
     :service_description "SSH"
     :check_command "check_ssh"}
    options)))
