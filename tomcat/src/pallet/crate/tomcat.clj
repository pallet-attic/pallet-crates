(ns pallet.crate.tomcat
  "Crate to install and configure tomcat.

   Installation sets the following host paramters:
     - [:tomcat :base] base of the tomcat installation
     - [:tomcat :config-path] path to the configuration files
     - [:tomcat :owner] user that owns and runs tomcat
     - [:tomcat :group] group for tomcat files
     - [:tomcat :service] the name of the package installed

   Configuration is via `server-configuration`, passing a server configuration
   generated with a call to `server`.

   The tomcat service may be controlled via `init-service`."
  (:refer-clojure :exclude [alias])
  (:require
   [pallet.action :as action]
   [pallet.action.directory :as directory]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.file :as file]
   [pallet.action.package :as package]
   [pallet.action.package.jpackage :as jpackage]
   [pallet.action.remote-file :as remote-file]
   [pallet.action.service :as service]
   [pallet.enlive :as enlive]
   [pallet.parameter :as parameter]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [pallet.template :as template]
   [pallet.thread-expr :as thread-expr]
   [net.cgrand.enlive-html :as enlive-html]
   [clojure.contrib.prxml :as prxml]
   [clojure.string :as string]))

(def
  ^{:doc "Baseline configuration file path" :private true}
  tomcat-config-root "/etc/")

(def
  ^{:doc "Baseline install path" :private true}
  tomcat-base "/var/lib/")

(def
  ^{:doc "package names for specific versions" :private true}
  version-package
  {6 "tomcat6"
   5 "tomcat5"})

(def
  ^{:doc "default package name for each packager" :private true}
  package-name
  {:aptitude "tomcat6"
   :pacman "tomcat"
   :yum "tomcat5"
   :amzn-linux "tomcat6"})

(def
 ^{:doc "default user and group name for each packager" :private true}
  tomcat-user-group-name
  {:aptitude "tomcat6"
   :pacman "tomcat"
   :yum "tomcat"})

(def ^{:doc "Flag for recognising changes to configuration"}
  tomcat-config-changed-flag "tomcat-config")

(declare default-settings-map)

(defn settings-map
  "Build a settings map for tomcat, containing all target independent
   configuration.
     :user        owner for the tomcat service
     :group       group for the tomcat service
     :version     tomcat major version
     :package     tomcat package name to install
     :service     tomcat service name
     :base-dir    location of tomcat install
     :config-dir  location of tomcat configuration files
     :use-jpackge flag to control use of jpackage
     :server      tomcat server definition (result of calling tomcat/server)
     :policies    tomcat policies, as a sequence of [seq-number name grants]"
  [{:keys [user group version package service base-dir config-dir use-jpackage
           server policies]
    :as options}]
  (merge default-settings-map options))

(defn settings
  "Calculates and attaches the settings to the request object, for later use."
  [session {:keys [user group version package service base-dir config-dir
                   use-jpackage server]
            :as settings-map}]
  (let [package (or package
                    (version-package version version)
                    (package-name (session/os-family session))
                    (package-name (session/packager session)))
        tomcat-user (tomcat-user-group-name (session/packager session))
        user (or user tomcat-user)
        group (or group tomcat-user)
        service (or service package)
        base-dir (or base-dir (str tomcat-base package "/"))
        config-dir (str tomcat-config-root package "/")
        use-jpackage (if (nil? use-jpackage)
                       (and
                        (= :centos (session/os-family session))
                        (re-matches
                         #"5\.[0-5]" (session/os-version session)))
                       use-jpackage)]
    (-> session
        (parameter/assoc-for-target
         [:tomcat]
         (merge
          settings-map
          {:package package
           :base base-dir
           :config-path config-dir
           :owner user
           :group group
           :service service
           :use-jpackage use-jpackage})))))

(defn install
  "Install tomcat from the standard package sources.

   On older centos versions, jpackge will be used to obtain tomcat 6 if
   version 6 is explicitly requested.

   Options controlling the installed package:
   - :version    specify the tomcat version (5, 6, or arbitrary string)
   - :package    the name of the package to install

   Options for specifying non-standard configuration used by the installed
   package:
   - :user       override the tomcat user
   - :group      override the tomcat group
   - :service    the name of the init service installed by the package
   - :base-dir   the install base used by the package
   - :config-dir the directory used for the configuration files"
  ;; ([session & {:keys [user group version package service
  ;;                    base-dir config-dir] :as options}]
  ;;    (->
  ;;     session
  ;;     (thread-expr/apply-map-> settings (settings-map options))
  ;;     install))
  [session & {:keys [action] :or {action :install} :as options}]
  (let [session (if (pos? (count (dissoc options :action)))
                  (settings session (settings-map options))
                  session)
        settings (parameter/get-for-target session [:tomcat])
        package (:package settings)
        user (:owner settings)
        group (:group settings)
        base-dir (:base settings)
        use-jpackage (:use-jpackage settings)
        pkg-options (when use-jpackage
                      {:enable
                       ["jpackage-generic" "jpackage-generic-updates"]})]
    (-> session
        (thread-expr/when->
         use-jpackage
         (jpackage/add-jpackage :releasever "5.0")
         (jpackage/package-manager-update-jpackage)
         (jpackage/jpackage-utils))
        (package/package-manager :update)
        (thread-expr/apply-map-> package/package package pkg-options)
        (directory/directory ;; fix jpackage ownership of tomcat home
         (stevedore/script (user-home ~user))
         :owner user :group group :mode "0755")
        (exec-script/exec-checked-script
         (format "Check tomcat is at %s" base-dir)
         (if-not (directory? ~base-dir)
           (do
             (println "Tomcat not installed at expected location")
             (exit 1)))))))

(defn tomcat
  "DEPRECATED: use install instead."
  [& args]
  (apply install args))

(defn init-service
  "Control the tomcat service.

   Specify `:if-config-changed true` to make actions conditional on a change in
   configuration.

   Other options are as for `pallet.action.service/service`. The service
   name is looked up in the request parameters."
  [session & {:keys [action if-config-changed if-flag] :as options}]
  (let [service (parameter/get-for-target session [:tomcat :service])
        options (if if-config-changed
                  (assoc options :if-flag tomcat-config-changed-flag)
                  options)]
    (-> session (thread-expr/apply-map-> service/service service options))))

(defn undeploy
  "Removes the named webapp directories, and any war files with the same base
   names."
  [session & app-names]
  (let [tomcat-base (parameter/get-for-target session [:tomcat :base])]
    (-> session
        (thread-expr/for-> [app-name app-names
                :let [app-name (or app-name "ROOT")
                      app-name (if (string? app-name) app-name (name app-name))
                      exploded-app-dir (str tomcat-base "webapps/" app-name)]]
               (directory/directory exploded-app-dir :action :delete)
               (file/file (str exploded-app-dir ".war") :action :delete)))))

(defn undeploy-all
  "Removes all deployed war file and exploded webapp directories."
  [session]
  (let [tomcat-base (parameter/get-for-target session [:tomcat :base])]
    (exec-script/exec-script
     session
     (~lib/rm ~(str tomcat-base "webapps/*") :r true :f true))))

(defn deploy
  "Copies a .war file to the tomcat server under webapps/${app-name}.war.  An
   app-name of \"ROOT\" or nil will deploy the source war file as the / webapp.

   Accepts options as for remote-file in order to specify the source.

   Other Options:
     :clear-existing true -- removes the existing exploded ${app-name} directory"
  [session app-name & {:as opts}]
  (let [tomcat-base (parameter/get-for-target session [:tomcat :base])
        tomcat-user (parameter/get-for-target session [:tomcat :owner])
        tomcat-group (parameter/get-for-target session [:tomcat :group])
        exploded-app-dir (str tomcat-base "webapps/" (or app-name "ROOT"))
        deployed-warfile (str exploded-app-dir ".war")
        options (merge
                 {:owner tomcat-user :group tomcat-group :mode 600}
                 (select-keys opts remote-file/all-options))]
    (->
     session
     (thread-expr/apply->
      remote-file/remote-file
      deployed-warfile
      (apply concat options))
     ;; (when-not-> (:clear-existing opts)
     ;;  ;; if we're not removing an existing, try at least to make sure
     ;;  ;; that tomcat has the permissions to explode the war
     ;;  (thread-expr/apply->
     ;;   directory/directory
     ;;   exploded-app-dir
     ;;   (apply concat
     ;;          (merge {:owner tomcat-user :group tomcat-group :recursive true}
     ;;                 (select-keys options [:owner :group :recursive])))))
     (thread-expr/when-> (:clear-existing opts)
             (directory/directory exploded-app-dir :action :delete)))))

(defn- output-grants
  "Return a string containing `grant` statements for a tomcat policy file."
  [[code-base permissions]]
  (let [code-base (when code-base
                    (format "codeBase \"%s\"" code-base))]
  (format
    "grant %s {\n  %s;\n};"
    (or code-base "")
    (string/join ";\n  " permissions))))

(defn policy
  "Configure tomcat policies.
     number - determines sequence i which policies are applied
     name - a name for the policy
     grants - a map from codebase to sequence of permissions"
  [session number name grants
   & {:keys [action] :or {action :create} :as options}]
  (let [tomcat-config-root (parameter/get-for-target
                            session [:tomcat :config-path])
        policy-file (str tomcat-config-root "policy.d/" number name ".policy")]
    (case action
      :create (->
               session
               (directory/directory
                (str tomcat-config-root "policy.d"))
               (remote-file/remote-file
                policy-file
                :content (string/join \newline (map output-grants grants))
                :literal true
                :flag-on-changed tomcat-config-changed-flag))
      :remove (file/file
               session policy-file :action :delete
               :flag-on-changed tomcat-config-changed-flag))))

(defn policies
  "Configure tomcat policies."
  [session]
  (let [settings (parameter/get-for-target session [:tomcat])
        tomcat-config-root (:config-path settings)
        policies (:policies settings)]
    (->
     session
     (thread-expr/for->
      [policy-values policies]
      (thread-expr/apply-> policy policy-values)))))

(defn application-conf
  "Configure tomcat applications.
   name - a name for the policy
   content - an xml application context"
  [session name content & {:keys [action] :or {action :create} :as options}]
  (let [tomcat-config-root (parameter/get-for-target
                            session [:tomcat :config-path])
        app-path (str tomcat-config-root "Catalina/localhost/")
        app-file (str app-path name ".xml")]
    (case action
      :create (->
               session
               (directory/directory app-path)
               (remote-file/remote-file
                app-file :content content :literal true
                :flag-on-changed tomcat-config-changed-flag))
      :remove (file/file session app-file :action :delete))))

(defn users*
  [roles users]
  (with-out-str
    (prxml/prxml
     [:decl {:version "1.1"}]
     [:tomcat-users
      (map #(vector :role {:rolename %}) roles)
      (map #(vector :user {:username (first %)
                           :password ((second %) :password)
                           :roles (string/join "," ((second %) :roles))})
           users)])))

(defn merge-tomcat-users [args]
  (loop [args args
         users {}
         roles []]
    (if (seq args)
      (if (= :role (first args))
        (recur (nnext args) users (conj roles (fnext args)))
        (recur (nnext args) (merge users {(first args) (fnext args)}) roles))
      [roles users])))

(action/def-collected-action user
  "Configure tomcat users. Options are:
     :role rolename
     username {:password \"pw\" :roles [\"role1\" \"role 2\"]}"
  {:arglists '([session & {:keys [role] :as options}])}
  [session args]
  (let [[roles users] (merge-tomcat-users (apply concat args))]
    (users* roles users)))

(def listener-classnames
     {:apr-lifecycle
      "org.apache.catalina.core.AprLifecycleListener"
      :jasper
      "org.apache.catalina.core.JasperListener"
      ::server-lifecycle
      "org.apache.catalina.mbeans.ServerLifecycleListener"
      ::global-resources-lifecycle
      "org.apache.catalina.mbeans.GlobalResourcesLifecycleListener"
     :jmx-remote-lifecycle
      "org.apache.catalina.mbeans.JmxRemoteLifecycleListener"
     :jre-memory-leak-prevention
      "org.apache.catalina.mbeans.JmxRemoteLifecycleListener"})

(def connector-classnames
     {})

(def resource-classnames
     {:sql-data-source "javax.sql.DataSource"})

(def valve-classnames
     {:access-log "org.apache.catalina.valves.AccessLogValve"
      :remote-addr "org.apache.catalina.valves.RemoteAddrValve"
      :remote-host "org.apache.catalina.valves.RemoteHostValve"
      :request-dumper "org.apache.catalina.valves.RequestDumperValve"
      :single-sign-on "org.apache.catalina.authenticator.SingleSignOn"
      :basic-authenticator "org.apache.catalina.authenticator.BasicAuthenticator"
      :digest-authenticator "org.apache.catalina.authenticator.DigestAuthenticator"
      :form-authenticator "org.apache.catalina.authenticator.FormAuthenticator"
      :ssl-authenticator "org.apache.catalina.authenticator.SSLAuthenticator"
      :webdav-fix "org.apache.catalina.valves.WebdavFixValve"
      :remote-ip "org.apache.catalina.valves.RemoteIpValve"})

(def realm-classnames
     {:jdbc "org.apache.catalina.realm.JDBCRealm"
      :data-source "org.apache.catalina.realm.DataSourceRealm"
      :jndi "org.apache.catalina.realm.JNDIRealm"
      :user-database "org.apache.catalina.realm.UserDatabaseRealm"
      :memory "org.apache.catalina.realm.MemoryRealm"
      :jaas "org.apache.catalina.realm.JAASRealm"
      :combined "org.apache.catalina.realm.CombinedRealm"
      :lock-out "org.apache.catalina.realm.LockOutRealm"})

(def *server-file* "server.xml")
(def *context-file* "context.xml")
(def *web-file* "web.xml")

(defn path-for
  "Get the actual filename corresponding to a template."
  [base] (str "crate/tomcat/" base))


(defn flatten-map
  "Flatten a map, removing all namespaced keywords and specified keys"
  [m & dissoc-keys]
  (apply concat (remove (fn [[k v]]
                          (and (keyword? k) (namespace k)))
                  (apply dissoc m dissoc-keys))))

(enlive/deffragment server-resources-transform
  [global-resources]
  [:Environment] (enlive/transform-if (global-resources ::environment) nil)
  [:Resource] (enlive/transform-if (global-resources ::resource) nil)
  [:Transaction] (enlive/transform-if (global-resources ::transaction) nil)
  [:GlobalNamingResources]
  (enlive-html/do-> ; ensure we have elements to configure
   (enlive/transform-if (global-resources ::environment)
                 (enlive-html/prepend (enlive/elt :Environment)))
   (enlive/transform-if (global-resources ::resource)
                 (enlive-html/prepend (enlive/elt ::resource)))
   (enlive/transform-if (global-resources ::transaction)
                 (enlive-html/prepend (enlive/elt :Transaction))))
  [:Environment]
  (enlive/transform-if (global-resources ::environment)
   (enlive-html/clone-for [environment (global-resources ::environment)]
                     (apply enlive-html/set-attr (flatten-map environment))))
  [:Resource]
  (enlive/transform-if (global-resources ::resource)
   (enlive-html/clone-for [resource (global-resources ::resource)]
                     (apply enlive-html/set-attr (flatten-map resource))))
  [:Transaction]
  (enlive/transform-if (global-resources ::transaction)
   (enlive-html/clone-for [transaction (global-resources ::transaction)]
                     (apply enlive-html/set-attr (flatten-map transaction)))))

(enlive/deffragment engine-transform
  [engine]
  [:Host] (enlive/transform-if (engine ::host) nil)
  [:Valve] (enlive/transform-if (engine ::valve) nil)
  [:Realm] (enlive/transform-if (engine ::realm) nil)
  [:Engine]
  (enlive-html/do-> ; ensure we have elements to configure
   (enlive/transform-if (engine ::host)
                 (enlive-html/prepend (enlive/elt :Host)))
   (enlive/transform-if (engine ::valve)
                 (enlive-html/prepend (enlive/elt :Valve)))
   (enlive/transform-if (engine ::realm)
                 (enlive-html/prepend (enlive/elt :Realm))))
  [:Host]
  (enlive/transform-if (engine ::host)
                (enlive-html/clone-for
                 [host (engine ::host)]
                 (enlive-html/do->
                  (apply enlive-html/set-attr (flatten-map host))
                  (engine-transform (engine ::host)))))
  [:Valve]
  (enlive/transform-if (engine ::valve)
                (enlive-html/clone-for
                 [valve (engine ::valve)]
                 (apply enlive-html/set-attr (flatten-map valve))))
  [:Realm]
  (enlive/transform-if (engine ::realm)
                (enlive-html/set-attr (flatten-map (engine ::realm)))))

(enlive/deffragment service-transform
  [service]
  [:Connector]
  (enlive-html/clone-for [connector (service ::connector)]
                    (apply enlive-html/set-attr (flatten-map connector)))
  [:Engine]
  (enlive/transform-if (service ::engine)
                (enlive-html/do->
                 (apply enlive-html/set-attr (flatten-map (service ::engine)))
                 (engine-transform (service ::engine)))))

(defn tomcat-server-xml
  "Generate server.xml content"
  [session server]
  {:pre [session]}
  (enlive/xml-emit
   (enlive/xml-template
    (path-for *server-file*) session [server]
    [:Listener]
    (enlive/transform-if (server ::listener) nil)
    [:GlobalNamingResources]
    (enlive/transform-if (server ::global-resources) nil)
    [:Service] (enlive/transform-if (server ::service)
                 (enlive-html/clone-for [service (server ::service)]
                   (service-transform service)))
    [:Server]
    (enlive-html/do->
     (enlive/transform-if (seq (apply concat (select-keys server [:port :shutdown])))
                   (apply enlive-html/set-attr
                          (apply concat (select-keys server [:port :shutdown]))))
     (enlive/transform-if (server ::listener)
                   (enlive-html/prepend (enlive/elt :Listener)))
     (enlive/transform-if (server ::global-resources)
                   (enlive-html/prepend
                    (enlive/elt :GlobalNamingResources))))
    [:Listener]
    (enlive/transform-if (server ::listener)
      (enlive-html/clone-for
       [listener (server ::listener)]
       (apply enlive-html/set-attr (flatten-map listener))))
    [:GlobalNamingResources]
    (enlive/transform-if (server ::global-resources)
      (server-resources-transform (server ::global-resources))))
   server))

(defn classname-for
  "Lookup value in the given map if it is a keyword, else return the value."
  [value classname-map]
  (if (keyword? value)
    (classname-map value)
    value))

(defn extract-member-keys [options]
  (loop [options (seq options)
         output []
         members #{}
         collections #{}]
    (if options
      (condp = (first options)
        :members (recur (nnext options) output (set (fnext options)) collections)
        :collections (recur (nnext options) output members (set (fnext collections)))
        (recur (next options) (conj output (first options)) members collections))
      [members collections output])))


(defn extract-nested-maps
  ""
  [[members collections options]]
  (let [pallet-type (fn [object]
                      (and (map? object) (object ::pallet-type)))
        add-member (fn [result object]
                     (if-let [pt (pallet-type object)]
                       (assoc result pt
                              (if (members pt)
                                object
                                (conj (or (result pt) []) object)))
                       result))
        members (reduce add-member {} options)
        options (filter (complement pallet-type) options)]
    (merge members (into {} (map vec (partition 2 options))))))


(defn extract-options [& options]
  (extract-nested-maps (extract-member-keys options)))

(defmacro pallet-type
  "Create a pallet type-map"
  [type-tag & options]
  `(assoc (apply extract-options ~@options) ::pallet-type ~type-tag))

(defn listener
  "Define a tomcat listener. listener-type is a classname or a key from
   `listener-classnames`.

   Options are listener-type specific, and match the attributes in the tomcat
   docs.

   For example, to configure the APR SSL support:
       (listener :apr-lifecycle :SSLEngine \"on\" :SSLRandomSeed \"builtin\")"
  [listener-type & options]
  (pallet-type ::listener
               :className (classname-for listener-type listener-classnames)
               options))

(defn global-resources
  "Define tomcat resources.
   Options include:
     resources, transactions, environments"
  [& options]
  (pallet-type ::global-resources
               :collections [::resource ::transaction ::environment]
               options))

(defn environment
  "Define tomcat environment variable.

   http://tomcat.apache.org/tomcat-6.0-doc/config/context.html#Environment_Entries"
  ([name value type]
     (pallet-type ::environment
                  [:name name :value value :type (.getName type)]))
  ([name value type override]
     (pallet-type ::environment
                  [:name name :value value :type (.getName type)
                   :override override])))

(defn resource
  "Define tomcat JNDI resource.
   resource-type is a classname, or on of :sql-datasource.
   Options include:

   http://tomcat.apache.org/tomcat-6.0-doc/config/resources.html
   "
  [name resource-type & options]
  (pallet-type ::resource
               :name name
               :type (classname-for resource-type resource-classnames)
               options))

(defn transaction
  "Define tomcat transaction factory."
  [factory-classname]
  (pallet-type ::transaction [:factory factory-classname]))

(defn service
  "Define a tomcat service.
   Requires an engine and connectors.

       (service (engine ) (connector)

   Options are implementation specific, and includes:
    - :className

   http://tomcat.apache.org/tomcat-6.0-doc/config/service.html"
  [& options]
  (pallet-type ::service :members [::engine] :collections [::connector] options))

(defn connector
  "Define a tomcat connector."
  [& options]
  (pallet-type ::connector options))

(defn ssl-jsse-connector
  "Define a SSL connector using JSEE.  This connector can be specified for a
   service.

   This connector has defaults equivalant to:
     (tomcat/connector :port 8443 :protocol \"HTTP/1.1\" :SSLEnabled \"true\"
       :maxThreads 150 :scheme \"https\" :secure \"true\"
       :clientAuth \"false\" :sslProtocol \"TLS\"
       :keystoreFile \"${user.home}/.keystore\" :keystorePass \"changeit\")"
  [& options]
  (pallet-type
   ::connector
   (concat [:port 8443 :protocol "HTTP/1.1" :SSLEnabled "true"
            :maxThreads 150 :scheme "https" :secure "true"
            :clientAuth "false" :sslProtocol "TLS"
            :keystoreFile "${user.home}/.keystore" :keystorePass "changeit"]
           options)))

(defn ssl-apr-connector
  "Define a SSL connector using APR.  This connector can be specified for a
   service.  You can use the :SSLEngine and :SSLRandomSeed options on the
   server's APR lifecycle listener to configure which engine is used.

   This connector has defaults equivalant to:
     (tomcat/connector :port 8443 :protocol \"HTTP/1.1\" :SSLEnabled \"true\"
       :maxThreads 150 :scheme \"https\" :secure \"true\"
       :clientAuth \"optional\" :sslProtocol \"TLSv1\"
       :SSLCertificateFile \"/usr/local/ssl/server.crt\"
       :SSLCertificateKeyFile=\"/usr/local/ssl/server.pem\")"
  [& options]
  (pallet-type
   ::connector
   (concat [:port 8443 :protocol "HTTP/1.1" :SSLEnabled "true"
            :maxThreads 150 :scheme "https" :secure "true"
            :clientAuth "optional" :sslProtocol "TLSv1"
            :SSLCertificateFile "/usr/local/ssl/server.crt"
            :SSLCertificateKeyFile="/usr/local/ssl/server.pem"]
           options)))

(defn engine
  "Define a tomcat engine.

   Accepts a realm, valves, and hosts.

   Options are specified in the tomcat docs, and include:
    - :className
    - :backgroundProcessorDelay
    - :jvmRoute

   http://tomcat.apache.org/tomcat-6.0-doc/config/engine.html"
  [name default-host & options]
  (pallet-type
   ::engine :members [::realm] :collections [::valve ::host]
   :name name :defaultHost default-host options))

;; TODO : Create specialised constructors for each realm
(defn realm
  "Define a tomcat realm.

   Options are specified in the tomcat docs, and realm-type specific.

   http://tomcat.apache.org/tomcat-6.0-doc/config/realm.html"
  [realm-type & options]
  (pallet-type
   ::realm :className (classname-for realm-type realm-classnames) options))

(defn valve
  "Define a tomcat valve.

   Options are specified in the tomcat docs, and valve-type specific.

   http://tomcat.apache.org/tomcat-6.0-doc/config/valve.html"
  [valve-type & options]
  (pallet-type
   ::valve :className (classname-for valve-type valve-classnames) options))

(defn host
  "Define a tomcat host.

   Accepts valves, contexts, aliases and listeners.

   Options are implementation specific, and include:
    - :autoDeploy
    - :backgroundProcessorDelay
    - :className
    - :deployOnStartup

   http://tomcat.apache.org/tomcat-6.0-doc/config/host.html"
  [name app-base & options]
  (pallet-type
   ::host :collections [::valve ::context ::alias ::listener]
   :name name :appBase app-base options))

(defn alias
  "Define a tomcat alias."
  [name]
  (pallet-type ::alias [:name name]))

(defn context
  "Define a tomcat context.

   Accepts: loader, manager realm, valves, listeners, resources, resource-links,
   parameters, environments, transactions and watched-resources

   Options are implemenation specific, and include:
    - :backgroundProcessorDelay
    - :className
    - :cookies
    - :crossContext
    - :docBase
    - :override
    - :privileged
    - :path
    - :reloadable
    - :sessionCookieDomain
    - :sessionCookieName
    - :sessionCookiePath
    - :wrapperClass
    - :useHttpOnly

   http://tomcat.apache.org/tomcat-6.0-doc/config/context.html"
  [name & options]
  (pallet-type
   ::context
   :members [::loader :manager ::realm]
   :collections [::valve ::listener ::resource ::resource-link :parameter
                 ::environment ::transaction :watched-resource]
   options))

(defn loader
  "Define a tomcat class loader.

   Options are implementation specific, and include:
    - :className
    - :delegate
    - :reloadable

   http://tomcat.apache.org/tomcat-6.0-doc/config/loader.html"
  [classname options]
  (pallet-type ::loader :className classname options))

(defn parameter
  "Define a tomcat parameter.

   Options are:
     - :description
     - :override

   http://tomcat.apache.org/tomcat-6.0-doc/config/context.html#Context_Parameters"
  [name value & options]
  (pallet-type ::parameters :name name :value value options))

(defn watched-resource
  "Define a tomcat watched resource. Used in a tomcat context."
  [name]
  (pallet-type ::watched-resources [:name name]))

(defn server
  "Define a tomcat server. Accepts server, listener and a global-resources
   form. The result of this function can be installed using
   `server-configuration`.

     - ::services         vector of services
     - ::global-resources vector of resources.

   Options include:
     - :class-name       imlementation class name - org.apache.catalina.Server
     - :port             shutdown listen port - 8005
     - :shutdown         shutdown command string - SHUTDOWN

       (server :port \"123\" :shutdown \"SHUTDOWNx\"
         (global-resources)
         (service
           (engine \"catalina\" \"host\"
             (valve :request-dumper))
             (connector :port \"8080\" :protocol \"HTTP/1.1\"
                :connectionTimeout \"20000\" :redirectPort \"8443\")))"
  [& options]
  {:pre [(not (map? (first options)))]} ; check not called as a crate function
  (pallet-type
   ::server
   :members [::global-resources]
   :collections [::listener ::service]
   options))

(defn server-configuration
  "Install a tomcat server configuration.

   The `server` argument can be generated with `pallet.crate.tomcat/server`.

   When a tomcat configuration element is not specified, the relevant section of
   the template is output, unmodified."
  ([session server]
     (->
      session
      (parameter/assoc-for-target [:tomcat :server] server)
      (server-configuration)))
  ([session]
     (let [base (parameter/get-for-target session [:tomcat :base])
           server (parameter/get-for-target session [:tomcat :server])]
       (->
        session
        (directory/directory (str base "conf"))
        (remote-file/remote-file
         (str base "conf/server.xml")
         :content (apply str (tomcat-server-xml session server))
         :flag-on-changed tomcat-config-changed-flag)))))

(def
  ^{:doc "Default settings map for tomcat configuration"}
  default-settings-map
  {:server (server)})
