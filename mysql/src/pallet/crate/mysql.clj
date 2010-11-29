(ns pallet.crate.mysql
  (:require
   [pallet.request-map :as request-map]
   [pallet.resource :as resource]
   [pallet.resource.exec-script :as exec-script]
   [pallet.resource.package :as package]
   [pallet.resource.service :as service]
   [pallet.parameter :as parameter]
   [pallet.stevedore :as stevedore]
   [clojure.string :as string])
  (:use
   [pallet.resource :only [defresource]]
   [pallet.stevedore :only [script]]
   [pallet.template :only [deftemplate apply-templates]]
   pallet.thread-expr))

(def mysql-my-cnf
     {:yum "/etc/my.cnf"
      :aptitude "/etc/mysql/my.cnf"})

(defn mysql-client
  [request]
  (package/packages
   request
   :yum [ "mysql-devel"]
   :aptitude [ "libmysqlclient15-dev" ]))

(defn- mysql-script*
  "MYSQL script invocation"
  [username password sql-script]
  (stevedore/script
   ("{\n" mysql "-u" ~username ~(str "--password=" password)
    ~(str "<<EOF\n" (string/replace sql-script "`" "\\`") "\nEOF\n}"))))

(def ^{:private true}
  sql-create-user "GRANT USAGE ON *.* TO %s IDENTIFIED BY '%s'")

(defn mysql-server
  "Install mysql server from packages"
  [request root-password & {:keys [start-on-boot] :or {start-on-boot true}}]
  (->
   request
   (package/package-manager
    :debconf
    (str "mysql-server-5.1 mysql-server/root_password password " root-password)
    (str "mysql-server-5.1 mysql-server/root_password_again password " root-password)
    (str "mysql-server-5.1 mysql-server/start_on_boot boolean " start-on-boot)
    (str "mysql-server-5.1 mysql-server-5.1/root_password password " root-password)
    (str "mysql-server-5.1 mysql-server-5.1/root_password_again password " root-password)
    (str "mysql-server-5.1 mysql-server/start_on_boot boolean " start-on-boot))
   (package/package "mysql-server")
   (when->
    (= :yum (request-map/packager request))
    (when->
     start-on-boot
     (service/service "mysqld" :action :enable))
    (service/service "mysqld" :action :start)
    (exec-script/exec-checked-script
     "Set Root Password"
     (chain-or
      ("/usr/bin/mysqladmin" -u root password (quoted ~root-password))
      (echo "Root password already set"))))
   (assoc-in [:parameters :mysql :root-password] root-password)))

(deftemplate my-cnf-template
  [request string]
  {{:path (mysql-my-cnf (:target-packager request))
    :owner "root" :mode "0440"}
   string})

(defresource mysql-conf
  "my.cnf configuration file for mysql"
  (mysql-conf*
   [request config]
   (apply-templates #(my-cnf-template request %) [config])))

(defn mysql-script
  "Execute a mysql script"
  [request username password sql-script]
  (exec-script/exec-checked-script
   request
   "MYSQL command"
   ~(mysql-script* username password sql-script)))

(defn create-database
  ([request name]
     (create-database
      request name "root"
      (parameter/get-for request [:mysql :root-password])))
  ([request name username root-password]
     (mysql-script
      request
      username root-password
      (format "CREATE DATABASE IF NOT EXISTS `%s`" name))))

(defn create-user
  ([request user password]
     (create-user
      request user password "root"
      (parameter/get-for request [:mysql :root-password])))
  ([request user password username root-password]
     (mysql-script
      request
      username root-password
      (format sql-create-user user password))))

(defn grant
  ([request privileges level user]
     (grant
      request privileges level user "root"
      (parameter/get-for request [:mysql :root-password])))
  ([request privileges level user username root-password]
     (mysql-script
      request
      username root-password
      (format "GRANT %s ON %s TO %s" privileges level user))))
