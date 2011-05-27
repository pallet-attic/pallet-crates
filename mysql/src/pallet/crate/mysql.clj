(ns pallet.crate.mysql
  (:require
   [pallet.action :as action]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.package :as package]
   [pallet.action.service :as service]
   [pallet.parameter :as parameter]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore]
   [pallet.template :as template]
   [clojure.string :as string])
  (:use
   pallet.thread-expr))

(def mysql-my-cnf
     {:yum "/etc/my.cnf"
      :aptitude "/etc/mysql/my.cnf"})

(defn mysql-client
  [session]
  (package/packages
   session
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
  [session root-password & {:keys [start-on-boot] :or {start-on-boot true}}]
  (->
   session
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
    (= :yum (session/packager session))
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

(template/deftemplate my-cnf-template
  [session string]
  {{:path (mysql-my-cnf (session/packager session))
    :owner "root" :mode "0440"}
   string})

(action/def-bash-action mysql-conf
  "my.cnf configuration file for mysql"
  [session config]
  (template/apply-templates #(my-cnf-template session %) [config]))

(defn mysql-script
  "Execute a mysql script"
  [session username password sql-script]
  (exec-script/exec-checked-script
   session
   "MYSQL command"
   ~(mysql-script* username password sql-script)))

(defn create-database
  ([session name]
     (create-database
      session name "root"
      (parameter/get-for session [:mysql :root-password])))
  ([session name username root-password]
     (mysql-script
      session
      username root-password
      (format "CREATE DATABASE IF NOT EXISTS `%s`" name))))

(defn create-user
  ([session user password]
     (create-user
      session user password "root"
      (parameter/get-for session [:mysql :root-password])))
  ([session user password username root-password]
     (mysql-script
      session
      username root-password
      (format sql-create-user user password))))

(defn grant
  ([session privileges level user]
     (grant
      session privileges level user "root"
      (parameter/get-for session [:mysql :root-password])))
  ([session privileges level user username root-password]
     (mysql-script
      session
      username root-password
      (format "GRANT %s ON %s TO %s" privileges level user))))
