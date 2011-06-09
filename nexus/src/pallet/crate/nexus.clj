(ns pallet.crate.nexus
  "Install and configure Nexus maven repository manager.

   See:
     http://www.sonatype.com/books/nexus-book/reference/install.html"
  (:require
   [pallet.action.directory :as directory]
   [pallet.action.environment :as environment]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.file :as file]
   [pallet.action.remote-directory :as remote-directory]
   [pallet.action.remote-file :as remote-file]
   [pallet.action.user :as user]
   [pallet.action.service :as service]
   [pallet.crate.etc-default :as etc-default]
   [pallet.crate.iptables :as iptables]
   [pallet.crate.java :as java]
   [pallet.parameter :as parameter]
   [pallet.script.lib :as lib]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore]
   [pallet.thread-expr :as thread-expr]))


(def ^{:doc "Flag for recognising changes to configuration"}
  nexus-config-changed-flag "nexus-config")

(def default-settings-map
  {:download-url
   "http://nexus.sonatype.org/downloads/nexus-oss-webapp-%s-bundle.tar.gz"
   :version "1.9.1.1"
   :install-path "/usr/local/nexus-%s"
   :base-path "/usr/local/nexus"
   :service-name "nexus"
   :user "nexus"
   :group "nexus"
   :port 8081})

(defn settings-map
  "Build a settings map for nexus.
      :version            nexus version to install
      :download-url       download url format string (with %s for version)
      :install-path       filesystem path format string (with %s for version)
      :base-path          filesystem path to symlink
      :sonatype-work-path filesystem path for nexus configuration

   Unrecognised options will be added to the main configuration file."
  [{:keys [version download-url install-path base-path]
    :as options}]
  (merge default-settings-map options))

(defn platform
  [session]
  (let [os-family (session/os-family session)
        base (cond
              (#{:windows} os-family) "windows-x86"
              (#{:solaris} os-family) "solaris-x86"
              :else "linux-x86")]
    (format "%s-%s" base (if (session/is-64bit? session) "64" "32"))))

(defn settings
  "Build settings for Nexus"
  [session settings-map]
  (let [version (:version settings-map)
        settings (->
                  settings-map
                  (update-in [:install-path] format version)
                  (update-in [:download-url] format version)
                  (update-in [:platform] #(or % (platform session)))
                  (update-in [:pid-dir]
                             #(or %
                                  (stevedore/script
                                   (str (~lib/pid-root) "/nexus")))))
        settings (->
                  settings
                  (update-in [:sonatype-work-path]
                             #(or % (str
                                     (:install-path settings)
                                     "/../sonatype-work"))))]
    (parameter/assoc-for-target session [:nexus] settings)))

(defn install
  "Install nexus"
  [session & {:keys [action] :or {action :install}}]
  (let [settings (parameter/get-for-target session [:nexus])
        version (:version settings)
        install-path  (:install-path settings)
        base-path  (:base-path settings)
        url (:download-url settings)
        group (:group settings)
        user (:user settings)
        pid-dir (:pid-dir settings)
        platform (:platform settings)
        wrapper (format "%s/bin/jsw/%s/wrapper" install-path platform)
        init-script (format "%s/bin/jsw/%s/nexus.pallet" install-path platform)
        init-script-in (format "%s/bin/jsw/%s/nexus" install-path platform)
        wrapper-conf (format
                      "%s/bin/jsw/conf/wrapper.conf" install-path platform)
        default-file (str
                      (stevedore/script (~lib/etc-default)) "/"
                      (:service-name settings))]
    (->
     session
     ;; user
     (user/group group :system true)
     (user/user user :system true :group group :shell "/bin/false")
     ;; download
     (remote-directory/remote-directory
      install-path :url url :md5-url (str url ".md5")
      :owner user :group group :recursive true)
     ;; symlink version to version indpendent link
     (file/symbolic-link install-path (:base-path settings) :force true)
     ;; ensure that the config directory exists
     (directory/directory
      (:sonatype-work-path settings) :owner user :group group)
     ;; convenience env var
     (environment/system-environment "nexus" {:NEXUS_HOME install-path})
     ;; install the service wrapper
     (directory/directory pid-dir :owner user :group group)
     (etc-default/write
      (:service-name settings)
      "APP_NAME" (:service-name settings)
      "APP_LONG_NAME" "Sonatype Nexus"
      "NEXUS_HOME" install-path
      "PLATFORM" platform
      "WRAPPER_CMD" wrapper
      "WRAPPER_CONF" wrapper-conf
      "PIDDIR" pid-dir
      "JAVA_HOME" (stevedore/script (~java/java-home))
      "PATH" (stevedore/script (str @PATH ":" (~java/java-home) "/bin"))
      "RUN_AS_USER" (:user settings))
     (remote-file/remote-file
      (format "%s/bin/jsw/conf/pallet.conf" install-path platform)
      :content (format "wrapper.pid=%s" pid-dir)
      :literal true
      :flag-on-changed nexus-config-changed-flag)
     (exec-script/exec-checked-script
      "Update wrapper.conf"
      (if (not @("fgrep" "pallet.conf" ~wrapper-conf))
        (do
          ("cat" ">>" ~wrapper-conf " <<'EOFpallet'")
          "#include ./pallet.conf"
          "EOFpallet")))
     (exec-script/exec-checked-script
      "Update init script"
      (cp ~init-script-in ~init-script)
      (~lib/sed-file
       ~init-script
       ~(str "i \\\\\n[ -f " default-file " ] && . " default-file)
       {:restriction "/Do not modify/"})
      ;; /var/run can be mounted as a tempfs on debians
      (~lib/sed-file
       ~init-script
       ~(str
         "i \\\\\n"
         "[ -d ${PIDDIR} ] "
         "|| { mkdir -p ${PIDDIR} && chown ${RUN_AS_USER}:${RUN_AS_USER} ; }")
       {:restriction "/Do not modify/"}))
     (service/init-script
      (:service-name settings)
      :remote-file init-script
      :flag-on-changed nexus-config-changed-flag))))


(defn service
  "Control nexus service"
    [session & {:keys [action if-config-changed if-flag] :as options}]
    (let [service (parameter/get-for-target session [:nexus :service-name])
          options (if if-config-changed
                    (assoc options :if-flag nexus-config-changed-flag)
                    options)]
      (-> session (thread-expr/apply-map-> service/service service options))))

(defn iptables-accept
  "Accept proxy sessions, by default on port 3128."
  ([session]
     (iptables-accept
      session (parameter/get-for-target session [:nexus :port] 8081)))
  ([session port]
     (iptables/iptables-accept-port session port)))
