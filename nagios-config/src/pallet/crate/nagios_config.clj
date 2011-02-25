(ns pallet.crate.nagios-config
  "Configures nodes to be monitored by nagios.

   `service` and `command` configure the nagios server to monitor the node
   against which they are executed.

   Some higher level configuration functions are provided for http monitoring.

   nrpe agent configuration is also supported.

   Tested on ubuntu 10.04"
  (:require
   [pallet.action.file :as file]
   [pallet.action.package :as package]
   [pallet.argument :as argument]
   [pallet.crate.iptables :as iptables]
   [pallet.crate.nagios :as nagios]
   [pallet.parameter :as parameter]
   [pallet.session :as session]
   [clojure.string :as string])
  (:use
   pallet.thread-expr))

(defn service
  "Configure nagios service monitoring.
     :servicegroups        name for service group(s) service should be part of
     :check_command        command for service
     :service_description  description for the service"
  [session {:keys [host_name] :as options}]
  (parameter/update-for session
   [:nagios :host-services
    (keyword (or host_name (nagios/nagios-hostname
                            (session/target-node session))))]
   (fn [x]
     (distinct
      (conj
       (or x [])
       options)))))

(defn command
  "Configure nagios command monitoring.
     :command_name name
     :command_line command line"
  [session & {:keys [command_name command_line] :as options}]
  (parameter/update-for
   session [:nagios :commands (keyword command_name)]
   (fn [_] command_line)))

(defn nrpe-client
  "Configure nrpe on machine to be monitored"
  [session]
  (-> session
      (package/package "nagios-nrpe-server")
      (when-let-> [server (parameter/get-for session [:nagios :server :ip] nil)]
                  (file/sed
                   "/etc/nagios/nrpe.cfg"
                   {"allowed_hosts=127.0.0.1"
                    (format "allowed_hosts=%s" server)}))))

(defn nrpe-client-port
  "Open the nrpe client port to the nagios server ip.  Used on the nagios server
  node to allow reporting via nrpe clients on monitored nodes."
  [session]
  (-> session
      (when-let-> [server (parameter/get-for session [:nagios :server :ip] nil)]
        (iptables/iptables-accept-port 5666 "tcp" :source server))))

(defn nrpe-check-load
  "Configure the nrpe check_load plugin."
  [session]
  (service
   session
   {:servicegroups [:machine]
    :check_command "check_nrpe_1arg!check_load"
    :service_description  "Current Load"}))

(defn nrpe-check-users
  "Configure the nrpe check_users plugin."
  [session]
  (service
   session
   {:servicegroups [:machine]
    :check_command "check_nrpe_1arg!check_users"
    :service_description  "Current Users"}))

(defn nrpe-check-disk
  "Configure the nrpe check_disk plugin.
   This checks the hda1 device."
  [session]
  (service
   session
   {:servicegroups [:machine]
    :check_command "check_nrpe_1arg!check_hda1"
    :service_description  "Root Disk"}))

(defn nrpe-check-total-procs
  "Configure the nrpe check_total_procs plugin."
  [session]
  (service
   session
   {:servicegroups [:machine]
    :check_command "check_nrpe_1arg!check_total_procs"
    :service_description  "Total Processes"}))

(defn nrpe-check-zombie-procs
  "Configure the nrpe check_zombie_procs plugin."
  [session]
  (service
   session
   {:servicegroups [:machine]
    :check_command "check_nrpe_1arg!check_zombie_procs"
    :service_description  "Zombie Processes"}))

(def ^{:private true :doc "List of valid options for check_http"}
  check-http-options
  #{:port :ssl :use-ipv4 :use-ipv6 :timeout :no-body :url
    :expect :string :method :certificate})

(defn- dissoc-keys
  [m keys]
  (apply dissoc m keys))

(defn monitor-http
  "Declare that a node's http service should be monitored by a nagios server.
   Uses the nagios `check_http` plugin to perform the checking, and options are
   passed to the plugin."
  [session & {:keys [port ssl use-ipv4 use-ipv6 timeout
   no-body url expect string method]
      :or {timeout 10}
      :as options}]
  (let [cmd (str
             "check_http_"
             (string/join
              ""
              (map name (filter check-http-options (keys options)))))]
    (-> session
        (command
         :command_name cmd
         :command_line
         (format
          "/usr/lib/nagios/plugins/check_http -I '$HOSTADDRESS$' %s --timeout=%s"
          (str
           (when port (format " --port=%d" port))
           (when ssl " --ssl")
           (when no-body " --no-body")
           (when use-ipv4 " --use-ipv4")
           (when use-ipv6 " --use-ipv6")
           (when url (format " --url=%s" url))
           (when expect (format " --expect=%s" expect))
           (when string (format " --string=%s" string))
           (when method (format " --method=%s" method)))
          timeout))
        (service
         (merge
          {:servicegroups [:http-services]
           :service_description (if ssl "HTTPS" "HTTP")}
          (->
           options
           (dissoc-keys check-http-options)
           (assoc :check_command cmd)))))))

(defn monitor-https-certificate
  "Declare that a node's https certificate should be monitored by a nagios
   server.  Uses the nagios `check_http` plugin to perform the checking, and
   options are passed to the plugin."
  [session & {:keys [port ssl use-ipv4
   use-ipv6 timeout certificate]
              :or {timeout 10 certificate 14}
              :as options}]
  (let [cmd (str
             "check_https_certificate"
             (string/join
              ""
              (map name (filter check-http-options (keys options)))))]
    (-> session
        (command
         :command_name cmd
         :command_line
         (format
          "/usr/lib/nagios/plugins/check_http -I '$HOSTADDRESS$' %s --timeout=%s"
          (str
           (when port (format " --port=%d" port))
           (when ssl " --ssl")
           (when use-ipv4 " --use-ipv4")
           (when use-ipv6 " --use-ipv6")
           (format " --certificate=%d" certificate))
          timeout))
        (service
         (merge
          {:servicegroups [:http-services]
           :service_description "HTTPS Certificate"}
          (->
           options
           (dissoc-keys check-http-options)
           (assoc :check_command cmd)))))))
