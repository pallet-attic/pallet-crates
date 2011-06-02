(ns pallet.crate.postgres
  "Install and configure PostgreSQL."
  (:require
   [pallet.action :as action]
   [pallet.action.directory :as directory]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.file :as file]
   [pallet.action.package :as package]
   [pallet.action.package.debian-backports :as debian-backports]
   [pallet.action.remote-file :as remote-file]
   [pallet.action.service :as service]
   [pallet.parameter :as parameter]
   [pallet.script.lib :as lib]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore]
   [pallet.thread-expr :as thread-expr]
   [clojure.contrib.condition :as condition]
   [clojure.contrib.logging :as logging]
   [clojure.string :as string])
  (:use
   pallet.thread-expr
   [pallet.script :only [defscript]]))

(def ^{:doc "Flag for recognising changes to configuration"}
  postgresql-config-changed-flag "postgresql-config")

(def default-settings-map
  {:version "9.0"
   :components #{:server :contrib}
   :port 5432
   :max_connections 100
   :ssl true
   :shared_buffers "24MB"
   :log_line_prefix "%t "
   :datestyle "iso, ymd"
   :default_text_search_config "pg_catalog.english"})

(defn settings-map
  "Build a settings map for postgresql.
      :version     postgresql version to install
      :components  postgresql components to install
      :permissions permissions to set in hba

   Unrecognised options will be added to the main configuration file."
  [{:keys [version components data_directory hba_file ident_file
           external_pid_file port max_connections unix_socket_directory
           ssl shared_buffers log_line_prefix datestyle
           default_text_search_config]
    :as options}]
  (merge default-settings-map options))

(defn package-source
  "Decide where to get the packages from"
  [session version]
  (let [os-family (session/os-family session)]
    (cond
     (and (= :debian os-family) (= "9.0" version)) :debian-backports
     (and (= :ubuntu os-family) (= "9.0" version)) :martin-pitt-backports
     (and (= :centos os-family) (= "9.0" version)) :pgdg
     (and (= :fedora os-family) (= "9.0" version)) :pgdg
     :else :native)))

(def pgdg-repo-versions
  {"9.0" "9.0-2"})

(defmulti default-settings
  "Determine the default settings for the specified "
  (fn [session os-family package-source settings]
    [os-family package-source]))

(defn base-settings [session]
  {:service "postgresql"
   :owner "postgres"
   :external_pid_file (str (stevedore/script (~lib/pid-root)) "/postgresql.pid")
   :unix_socket_directory (str (stevedore/script (~lib/pid-root))
                               "/postgresql")
   :initdb-via :service})

(defmethod default-settings [:debian :native]
  [session os-family package-source settings]
  (let [version (:version settings)]
    (merge
     (base-settings session)
     {:packages ["postgresql"]
      :data_directory (format "/var/lib/postgresql/%s/main" version)
      :postgresql_file (format
                        "/etc/postgresql/%s/main/postgresql.conf" version)
      :hba_file (format "/etc/postgresql/%s/main/pg_hba.conf" version)
      :ident_file (format "/etc/postgresql/%s/main/pg_ident.conf" version)
      :external_pid_file (format "/var/run/postgresql/%s-main.pid" version)
      :unix_socket_directory "/var/run/postgresql"})))

(defmethod default-settings [:rh :native]
  [session os-family package-source settings]
  (let [version (:version settings)]
    (merge
     (base-settings session)
     {:packages (map
                 #(str "postgresql-" (name %))
                 (:components settings #{:server :libs}))
      :data_directory (format "/var/lib/pgsql/%s/" version)
      :postgresql_file (format "/var/lib/pgsql/%s/postgresql.conf" version)
      :hba_file (format "/var/lib/pgsql/%s/pg_hba.conf" version)
      :ident_file (format "/var/lib/pgsql/%s/pg_ident.conf" version)
      :external_pid_file (format "/var/run/pgsql/%s.pid" version)
      :unix_socket_directory "/var/run/pgsql"})))

(defmethod default-settings [:rh :pgdg]
  [session os-family package-source settings]
  (let [version (:version settings)]
    (merge
     (base-settings session)
     (default-settings session :rh :native settings)
     {:packages (map
                 #(str "postgresql" (string/replace version "." "")
                       "-" (name %))
                 (:components settings))
      :data_directory (format "/var/lib/pgsql/%s" version)
      :postgresql_file (format "/var/lib/pgsql/%s/postgresql.conf" version)
      :hba_file (format "/var/lib/pgsql/%s/pg_hba.conf" version)
      :ident_file (format "/var/lib/pgsql/%s/pg_ident.conf" version)
      :external_pid_file (format "/var/run/pgsql/%s.pid" version)
      :service (str "postgresql-" version)})))

(defmethod default-settings [:arch :native]
  [session os-family package-source settings]
  (let [version (:version settings)]
    (merge
     (base-settings session)
     {:components []
      :packages ["postgresql"]
      :data_directory "/var/lib/postgres/data/"
      :postgresql_file  "/var/lib/postgres/data/postgresql.conf"
      :hba_file  "/var/lib/postgres/data/pg_hba.conf"
      :ident_file "/var/lib/postgres/data/pg_ident.conf"
      :initdb-via :initdb})))

(defmethod default-settings [:debian :debian-backports]
  [session os-family package-source settings]
  (let [version (:version settings)]
    (merge
     (default-settings session :debian :native settings)
     {:packages [(str "postgresql-" version)]})))

(defmethod default-settings [:debian :martin-pitt-backports]
  [session os-family package-source settings]
  (default-settings session :debian :debian-backports settings))

(def non-options-in-settings
  #{:components :version :permissions :initdb-via :package-source :packages
    :service :owner})

(defn settings
  "Build a settings for postgresql"
  [session settings-map]
  (let [os-family (session/os-family session)
        os-base (session/base-distribution session)
        components (:components settings-map)
        version (:version settings-map)
        package-source (package-source session version)
        settings (merge
                  (default-settings
                    session os-base package-source settings-map)
                  {:package-source package-source}
                  settings-map)]
    (parameter/assoc-for-target
     session
     [:postgresql :settings]
     (assoc (select-keys settings non-options-in-settings)
       :options (apply dissoc settings non-options-in-settings)))))

(defn postgres
  "Version should be a string identifying the major.minor version number desired
   (e.g. \"9.0\")."
  ([session]
     (let [os-family (session/os-family session)
           settings (parameter/get-for-target session [:postgresql :settings])
           packages (:packages settings)
           package-source (:package-source settings)
           version (:version settings)]
       (logging/info
        (format "postgresql %s from %s packages [%s]"
                version (name package-source) (string/join ", " packages)))
       (->
        session
        (when-> (= package-source :martin-pitt-backports)
                (package/package-source
                 "Martin Pitt backports"
                 :aptitude {:url "ppa:pitti/postgresql"})
                (package/package-manager :update))
        (when-> (= package-source :debian-backports)
                (debian-backports/add-debian-backports)
                (package/package-manager :update)
                (package/package
                 "libpq5"
                 :enable (str
                          (stevedore/script (~lib/os-version-name))
                          "-backports")))
        (when->
         (= package-source :pgdg)
         (action/with-precedence {:action-id ::add-pgdg-rpm
                                  :always-before `package/package}
           (package/add-rpm
            "pgdg.rpm"
            :url (format
                  "http://yum.pgrpms.org/reporpms/%s/pgdg-%s-%s.noarch.rpm"
                  version (name os-family) (pgdg-repo-versions version))))
         (action/with-precedence {:action-id ::pgdg-update
                                  :always-before `package/package
                                  :always-after ::add-pgdg-rpm}
           (package/package-manager :update)))
        ;; install packages
        (arg-> [session]
               (for-> [package (:packages settings)]
                      (package/package package))))))
  ;; this is to preserve API compatibility
  ([session version]
     (->
      session
      (settings
       (merge
        default-settings-map
        (parameter/get-for-target session [:postgresql :settings] nil)
        {:version version}))
      (postgres))))


(def ^{:private true} pallet-cfg-preamble
"# This file was auto-generated by Pallet. Do not edit it manually unless you
# know what you are doing. If you are still using Pallet, you probably want to
# edit your Pallet scripts and rerun them.\n")

;;
;; pg_hba.conf
;;

(def ^{:private true}
  auth-methods #{"trust" "reject" "md5" "password" "gss" "sspi" "krb5"
                                     "ident" "ldap" "radius" "cert" "pam"})
(def ^{:private true}
  ip-addr-regex #"[0-9]{1,3}.[0-9]{1,3}+.[0-9]{1,3}+.[0-9]{1,3}+")

(defn- valid-hba-record?
  "Takes an hba-record as input and minimally checks that it could be a valid
   record."
  [{:keys [connection-type database user auth-method address ip-mask]
    :as record-map}]
  (and (#{"local" "host" "hostssl" "hostnossl"} (name connection-type))
       (every? #(not (nil? %)) [database user auth-method])
       (auth-methods (name auth-method))))

(defn- record-to-map
  "Takes a record given as a map or vector, and turns it into the map version."
  [record]
  (cond
   (map? record) record
   (vector? record) (case (name (first record))
                      "local" (apply
                               hash-map
                               (interleave
                                [:connection-type :database :user :auth-method
                                 :auth-options]
                                record))
                      ("host"
                       "hostssl"
                       "hostnossl") (let [[connection-type database user address
                                           & remainder] record]
                                      (if (re-matches
                                           ip-addr-regex (first remainder))
                                        ;; Not nil so must be an IP mask.
                                        (apply
                                         hash-map
                                         (interleave
                                          [:connection-type :database :user
                                           :address :ip-mask :auth-method
                                           :auth-options]
                                          record))
                                        ;; Otherwise, it may be an auth-method.
                                        (if (auth-methods
                                             (name (first remainder)))
                                          (apply
                                           hash-map
                                           (interleave
                                            [:connection-type :database :user
                                             :address :auth-method
                                             :auth-options]
                                            record))
                                          (condition/raise
                                           :type :postgres-invalid-hba-record
                                           :message
                                           (format
                                            "The fifth item in %s does not appear to be an IP mask or auth method."
                                            (name record))))))
                       (condition/raise
                        :type :postgres-invalid-hba-record
                        :message (format
                                  "The first item in %s is not a valid connection type."
                                  (name record))))
   :else
   (condition/raise :type :postgres-invalid-hba-record
                    :message (format "The record %s must be a vector or map."
                                     (name record)))))

(defn- format-auth-options
  "Given the auth-options map, returns a string suitable for inserting into the
   file."
  [auth-options]
  (string/join "," (map #(str (first %) "=" (second %)) auth-options)))

(defn- format-hba-record
  [record]
  (let [record-map (record-to-map record)
        record-map (assoc record-map :auth-options
                          (format-auth-options (:auth-options record-map)))
        ordered-fields (map #(% record-map "")
                            [:connection-type :database :user :address :ip-mask
                             :auth-method :auth-options])
        ordered-fields (map name ordered-fields)]
    (if (valid-hba-record? record-map)
      (str (string/join "\t" ordered-fields) "\n"))))

(defn hba-conf
  "Generates a pg_hba.conf file from the arguments. Each record is either a
   vector or map of keywords/args.

   Note that pg_hba.conf is case-sensitive: all means all databases, ALL is a
   database named ALL.

   Also note that if you intend to execute subsequent commands, you'd do best to
   include entries in here that allow the admin user you are using easy access
   to the database. For example, allow the postgres user to have ident access
   over local.

   Options:
   :records     - A sequence of records (either vectors or maps of
                  keywords/strings).
   :conf-path   - A format string for the full file path, with a %s for the
                  version."
  [session & {:keys [records conf-path]}]
  (let [settings (parameter/get-for-target session [:postgresql :settings] {})
        version (:version settings)
        records (or records (:permissions settings) [])
        conf-path (or
                   (when-let [d conf-path] (format d version))
                   (-> settings :options :hba_file))
        hba-contents (apply str pallet-cfg-preamble
                            (map format-hba-record records))]
    (-> session
        (remote-file/remote-file conf-path
         :content hba-contents
         :literal true
         :flag-on-changed postgresql-config-changed-flag
         :owner (:owner settings)))))

;;
;; postgresql.conf
;;

(defn- parameter-escape-string
  "Given a string, escapes any single-quotes."
  [string]
  (apply str (replace {\' "''"} string)))

(defn- format-parameter-value
  [value]
  (cond (number? value)
        (str value)
        (string? value)
        (str "'" value "'")
        (vector? value)
        (str "'" (string/join "," (map name value)) "'")
        (or (= value true) (= value false))
        (str value)
        :else
        (condition/raise
         :type :postgres-invalid-parameter
         :message (format
                   (str
                    "Parameters must be numbers, strings, or vectors of such. "
                    "Invalid value %s") (pr-str value))
         :value value)))

(defn- format-parameter
  "Given a key/value pair in a vector, formats it suitably for the
   postgresql.conf file.
   The value should be either a number, a string, or a vector of such."
  [[key value]]
  (let [key-str (name key)
        parameter-str (format-parameter-value value)]
    (str key-str " = " parameter-str "\n")))

(defn postgresql-conf
  "Generates a postgresql.conf file from the arguments.
   Example: (postgresql-conf
              :options {:listen_address [\"10.0.1.1\",\"localhost\"]})
         => listen_address = '10.0.1.1,localhost'

   Options:
   :options     - A map of parameters (string(able)s, numbers, or vectors of
                  such).
   :conf-path   - A format string for the file path, with a %s for
                  the version."
  [session & {:keys [options conf-path]}]
  (let [settings (parameter/get-for-target session [:postgresql :settings] {})
        version (:version settings)
        conf-path (or
                   conf-path
                   (when-let [d conf-path] (format d version))
                   (-> settings :options :postgresql_file))
        options (or options (:options settings))
        contents (apply str pallet-cfg-preamble (map format-parameter options))]
    (remote-file/remote-file
     session conf-path :content contents :literal true :owner (:owner settings)
     :flag-on-changed postgresql-config-changed-flag)))

(declare service)

(defn initdb
  "Initialise a db"
  [session]
  (let [settings (parameter/get-for-target session [:postgresql :settings] {})
        initdb-via (:initdb-via settings :initdb)
        data-dir (-> settings :options :data_directory)]
    (case initdb-via
          :service (service session :action :initdb)
          :initdb (->
                   session
                   (directory/directory
                    data-dir
                    :owner (:owner settings "postgres")
                    :mode "0755"
                    :path true)
                   (exec-script/exec-checked-script
                    "initdb"
                    (sudo -u ~(:owner settings "postgres")
                          initdb -D ~data-dir))))))

;;
;; Scripts
;;

(defn postgresql-script
  "Execute a postgresql script.

   Options for how this script should be run:
     :as-user username       - Run this script having sudoed to this (system)
                               user. Default: postgres
     :ignore-result          - Ignore any error return value out of psql."
  [session sql-script & {:keys [as-user ignore-result] :as options}]
  (let [settings (parameter/get-for-target session [:postgresql :settings] {})
        as-user (or as-user (-> settings :owner))]
    (-> session
        (exec-script/exec-checked-script
         "PostgreSQL temp command file"
         (var psql_commands (~lib/make-temp-file "postgresql")))
        (remote-file/remote-file
         (stevedore/script @psql_commands)
         :no-versioning true
         :literal true
         :content sql-script)
        (exec-script/exec-script
         ;; Note that we stuff all output. This is because certain commands in
         ;; PostgreSQL are idempotent but spit out an error and an error exit
         ;; anyways (eg, create database on a database that already exists does
         ;; nothing, but is counted as an error).
         ("{\n" sudo "-u" ~as-user psql "-f" @psql_commands > "/dev/null" "2>&1"
          ~(when ignore-result "|| 0") "\n}"))
        (remote-file/remote-file
         (stevedore/script @psql_commands)
         :action :delete))))

(defn create-database
  "Create a database if it does not exist.

   You can specify database parameters by including a keyed parameter called
   :db-parameters, which indicates a vector of strings or keywords that will get
   translated in order to the options to the create database command. Passes on
   key/value arguments it does not understand to postgresql-script.

   Example: (create-database
              \"my-database\" :db-parameters [:encoding \"'LATIN1'\"])"
  [session db-name & rest]
  (let [{:keys [db-parameters] :as options} rest
        db-parameters-str (string/join " " (map name db-parameters))]
    ;; Postgres simply has no way to check if a database exists and issue a
    ;; "CREATE DATABASE" only in the case that it doesn't. That would require a
    ;; function, but create database can't be done within a transaction, so
    ;; you're screwed. Instead, we just use the fact that trying to create an
    ;; existing database does nothing and stuff the output/error return.
    (apply postgresql-script
           session
           (format "CREATE DATABASE %s %s;" db-name db-parameters-str)
           (conj (vec rest) :ignore-result true))))

;; This is a format string that generates a temporary PL/pgsql function to
;; check if a given role exists, and if not create it. The first argument
;; should be the role name, the second should be any user-parameters.
(def ^{:private true} create-role-pgsql
"create or replace function pg_temp.createuser() returns void as $$
 declare user_rec record;
 begin
 select into user_rec * from pg_user where usename='%1$s';
 if user_rec.usename is null then
     create role %1$s %2$s;
 end if;
 end;
 $$ language plpgsql;
 select pg_temp.createuser();")

(defn create-role
  "Create a postgres role if it does not exist.

   You can specify user parameters by including a keyed parameter called
   :user-parameters, which indicates a vector of strings or keywords that will
   get translated in order to the options to the create user command. Passes on
   key/value arguments to postgresql-script.

   Example (create-role
             \"myuser\" :user-parameters [:encrypted :password \"'mypasswd'\"])"
  [session username & rest]
  (let [{:keys [user-parameters] :as options} rest
        user-parameters-str (string/join " " (map name user-parameters))]
    (apply postgresql-script
           session
           (format create-role-pgsql username user-parameters-str)
           rest)))


(defn service
  "Control the postgresql service.

   Specify `:if-config-changed true` to make actions conditional on a change in
   configuration.

   Other options are as for `pallet.action.service/service`. The service
   name is looked up in the request parameters."
  [session & {:keys [action if-config-changed if-flag] :as options}]
  (let [service (parameter/get-for-target
                 session [:postgresql :settings :service])
        options (if if-config-changed
                  (assoc options :if-flag postgresql-config-changed-flag)
                  options)]
    (-> session (thread-expr/apply-map-> service/service service options))))
