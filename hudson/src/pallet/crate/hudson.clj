(ns pallet.crate.hudson
 "Installation of hudson"
  (:require
   [pallet.action :as action]
   [pallet.action.directory :as directory]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.remote-file :as remote-file]
   [pallet.action.service :only [service]]
   [pallet.action.user :as user]
   [pallet.config-file.format :as format]
   [pallet.crate.maven :as maven]
   [pallet.crate.tomcat :as tomcat]
   [pallet.enlive :as enlive]
   [pallet.parameter :as parameter]
   [pallet.stevedore :as stevedore]
   [pallet.thread-expr :as thread-expr]
   [pallet.utils :as utils]
   [clojure.contrib.def :as def]
   [clojure.contrib.logging :as logging]
   [clojure.contrib.prxml :as prxml]
   [net.cgrand.enlive-html :as xml]
   [clojure.string :as string])
  (:import
   org.apache.commons.codec.binary.Base64))

(def hudson-data-path "/var/lib/hudson")
(def hudson-owner "root")
(def hudson-user  "hudson")
(def hudson-group  "hudson")

(def/defvar- *config-file* "config.xml")
(def/defvar- *user-config-file* "users/config.xml")
(def/defvar- *maven-file* "hudson.tasks.Maven.xml")
(def/defvar- *maven2-job-config-file* "job/maven2_config.xml")
(def/defvar- *ant-job-config-file* "job/ant_config.xml")
(def/defvar- *git-file* "scm/git.xml")
(def/defvar- *svn-file* "scm/svn.xml")
(def/defvar- *ant-file* "hudson.tasks.Ant.xml")

(defn path-for
  "Get the actual filename corresponding to a template."
  [base] (str "crate/hudson/" base))

(def hudson-base-url "http://mirrors.jenkins-ci.org/")
(def hudson-download-base-url (str hudson-base-url "war/"))

(defn hudson-url
  "Calculate the url for the specified version"
  [version]
  (if (= version :latest)
    (str hudson-download-base-url "latest/jenkins.war")
    (str hudson-download-base-url version "/"
         (if (< (java.lang.Float/parseFloat (str version)) 1.396)
           "hudson" "jenkins")
         ".war")))

(def hudson-md5
     {"1.395" "6af0fb753a099616c74104c60d6b26dd"
      "1.377" "81b602c754fdd28cc4d57a9b82a7c1f0"
      "1.355" "5d616c367d7a7888100ae6e98a5f2bd7"})

(defn tomcat-deploy
  "Install hudson on tomcat.
     :version version-string   - specify version, eg 1.355, or :latest"
  [session & {:keys [version] :or {version :latest} :as options}]
  (logging/trace (str "Hudson - install on tomcat"))
  (let [user (parameter/get-for-target session [:tomcat :owner])
        group (parameter/get-for-target session [:tomcat :group])
        file (str hudson-data-path "/hudson.war")]
    (->
     session
     (parameter/assoc-for-target
      [:hudson :data-path] hudson-data-path
      [:hudson :owner] hudson-owner
      [:hudson :user] user
      [:hudson :group] group)
     (directory/directory
      hudson-data-path :owner hudson-owner :group group :mode "0775")
     (remote-file/remote-file
      file :url (hudson-url version)
      :md5 (hudson-md5 version))
     (tomcat/policy
      99 "hudson"
      {(str "file:${catalina.base}/webapps/hudson/-")
       ["permission java.security.AllPermission"]
       (str "file:" hudson-data-path "/-")
       ["permission java.security.AllPermission"]})
     (tomcat/application-conf
      "hudson"
      (format "<?xml version=\"1.0\" encoding=\"utf-8\"?>
 <Context
 privileged=\"true\"
 path=\"/hudson\"
 allowLinking=\"true\"
 swallowOutput=\"true\"
 >
 <Environment
 name=\"HUDSON_HOME\"
 value=\"%s\"
 type=\"java.lang.String\"
 override=\"false\"/>
 </Context>"
              hudson-data-path))
     (tomcat/deploy "hudson" :remote-file file))))

(defn tomcat-undeploy
  "Remove hudson on tomcat"
  [session]
  (logging/trace (str "Hudson - uninistall from tomcat"))
  (let [hudson-data-path (parameter/get-for-target
                           session [:hudson :data-path])
        file (str hudson-data-path "/hudson.war")]
    (->
     session
     (parameter/assoc-for-target [:hudson] nil)
     (tomcat/undeploy "hudson")
     (tomcat/policy 99 "hudson" nil :action :remove)
     (tomcat/application-conf "hudson" nil :action :remove)
     (directory/directory
      hudson-data-path :action :delete :force true :recursive true))))

(defn download-cli [session]
  (let [user (parameter/get-for-target session [:hudson :admin-user])
        pwd (parameter/get-for-target session [:hudson :admin-password])]
    (remote-file/remote-file
     session
     "hudson-cli.jar"
     :url (if user
            (format
             "http://%s:%s@localhost:8080/hudson/jnlpJars/hudson-cli.jar"
             user pwd)
            "http://localhost:8080/hudson/jnlpJars/hudson-cli.jar"))))

(defn cli [session command]
  (let [user (parameter/get-for-target session [:hudson :admin-user])
        pwd (parameter/get-for-target session [:hudson :admin-password])]
    (format
     "java -jar ~/hudson-cli.jar -s http://localhost:8080/hudson %s %s"
     command
     (if user (format "--username %s --password %s" user pwd) ""))))

(defn hudson-cli
  "Install a hudson cli."
  [session]
  (download-cli session))

(def hudson-plugin-urls
  {:git "http://hudson-ci.org/latest/git.hpi"})

(defn install-plugin [session url]
  (str (cli session (str "install-plugin " (utils/quoted url)))))

(defn plugin-via-cli
  "Install a hudson plugin.  The plugin should be a keyword.
  :url can be used to specify a string containing the download url"
  [session plugin & {:keys [url] :as options}]
  {:pre [(keyword? plugin)]}
  (logging/debug (str "Hudson - add plugin " plugin))
  (let [src (or url (plugin hudson-plugin-urls))]
    (-> session
        (hudson-cli)
        (exec-script/exec-checked-script
         (format "installing %s plugin" plugin)
         ~(install-plugin src)))))


(defn cli-command
  "Execute a maven cli command"
  [session message command]
  (-> session
      (hudson-cli)
      (exec-script/exec-checked-script
       message
       ~(str (cli session command)))))

(defn version
  "Show running version"
  [session]
  (cli-command session "Hudson Version: " "version"))

(defn reload-configuration
  "Show running version"
  [session]
  (cli-command session "Hudson reload-configuration: " "reload-configuration"))

(defn build
  "Build a job"
  [session job]
  (cli-command session (format "build %s: " job) (format "build %s" job)))

(defn truefalse [value]
  (if value "true" "false"))

(def security-realm-class
  {:hudson "hudson.security.HudsonPrivateSecurityRealm"})

(def authorization-strategy-class
  {:global-matrix "hudson.security.GlobalMatrixAuthorizationStrategy"})

(def permission-class
  {:computer-configure "hudson.model.Computer.Configure"
   :computer-delete "hudson.model.Computer.Delete"
   :hudson-administer "hudson.model.Hudson.Administer"
   :hudson-read "hudson.model.Hudson.Read"
   :item-build "hudson.model.Item.Build"
   :item-configure "hudson.model.Item.Configure"
   :item-create "hudson.model.Item.Create"
   :item-delete "hudson.model.Item.Delete"
   :item-read "hudson.model.Item.Read"
   :item-workspace "hudson.model.Item.Workspace"
   :run-delete "hudson.model.Run.Delete"
   :run-update "hudson.model.Run.Update"
   :scm-tag "hudson.scm.SCM.Tag"
   :view-configure "hudson.model.View.Configure"
   :view-create "hudson.model.View.Create"
   :view-delete "hudson.model.View.Delete"})

(def all-permissions
  [:computer-configure :computer-delete :hudson-administer :hudson-read
   :item-build :item-configure :item-create :item-delete :item-read
   :item-workspace :run-delete :run-update :scm-tag :view-configure
   :view-create :view-delete])

(defmulti plugin-config
  "Plugin specific configuration."
  (fn [session plugin options] plugin))

(defmethod plugin-config :git
  [session plugin _]
  (user/user
   session
   (parameter/get-for-target session [:hudson :user])
   :action :manage :comment "hudson"))

(defmethod plugin-config :ircbot
  [session plugin options]
  (let [hudson-data-path (parameter/get-for-target
                          session [:hudson :data-path])
        hudson-owner (parameter/get-for-target
                      session [:hudson :owner])
        hudson-group (parameter/get-for-target
                      session [:hudson :group])]
    (remote-file/remote-file
     session
     (format "%s/hudson.plugins.ircbot.IrcPublisher.xml" hudson-data-path)
     :content (with-out-str
                (prxml/prxml
                 [:decl! {:version "1.0"}]
                 [:hudson.plugins.ircbot.IrcPublisher_-DescriptorImpl {}
                  [:enabled {} (truefalse (:enabled options))]
                  [:hostname {} (:hostname options)]
                  [:port {} (:port options 6674)]
                  [:password {} (:password options)]
                  [:nick {} (:nick options)]
                  [:nickServPassword {} (:nick-serv-password options)]
                  [:defaultTargets (if (seq (:default-targets options))
                                     {}
                                     {:class "java.util.Collections$EmptyList"})
                   (map #(prxml/prxml
                          [:hudson.plugins.im.GroupChatIMMessageTarget {}
                           [:name {} (:name %)]
                           [:password {} (:password %)]])
                        (:default-targets options))]
                  [:commandPrefix {}  (:command-prefix options)]
                  [:hudsonLogin {}  (:hudson-login options)]
                  [:hudsonPassword {}  (:hudson-password options)]
                  [:useNotice {}  (truefalse (:use-notice options))]]))
     :literal true
     :owner hudson-owner :group hudson-group :mode "664")))

(defmethod plugin-config :default [session plugin options]
  session)


(defmulti plugin-property
  "Plugin specific job property."
  (fn [[plugin options]] plugin))

(def property-names
  {:authorization-matrix "hudson.security.AuthorizationMatrixProperty"
   :disk-usage "hudson.plugins.disk__usage.DiskUsageProperty"
   :github "com.coravy.hudson.plugins.github.GithubProjectProperty"
   :jira "hudson.plugins.jira.JiraProjectProperty"
   :shelve-project-plugin
     "org.jvnet.hudson.plugins.shelveproject.ShelveProjectProperty"})

;; default implementation looks up the property in the `property-names` map
;; and adds tags for each of the entries in `options`
(defmethod plugin-property :default [[plugin options]]
  {:tag (property-names plugin)
   :content (map
             #(hash-map :tag (name (key %)) :content (str (val %)))
             options)})

(defmethod plugin-property :authorization-matrix [[plugin options]]
  {:tag (property-names plugin)
   :content (mapcat
             (fn [{:keys [user permissions]}]
               (map
                (fn [permission]
                  {:tag "permission"
                   :content (format
                             "%s:%s" (permission-class permission) user)})
                permissions))
             options)})

(def hudson-plugin-latest-url "http://updates.hudson-labs.org/latest/")
(def hudson-plugin-base-url "http://mirrors.jenkins-ci.org/plugins/")

(def ^{:doc "allow overide of urls"}
  hudson-plugins {})

(defn default-plugin-path
  [plugin version]
  (if (= :latest version)
    (str hudson-plugin-latest-url (name plugin) ".hpi")
    (str hudson-plugin-base-url (name plugin) "/" version "/"
         (name plugin) ".hpi")))

(defn plugin
  "Install a hudson plugin.  The plugin should be a keyword.
   :url can be used to specify a string containing the download url"
  [session plugin & {:keys [url md5 version]
                     :or {version :latest}
                     :as options}]
  {:pre [(keyword? plugin)]}
  (logging/debug (str "Hudson - add plugin " plugin))
  (let [src (merge
             {:url (default-plugin-path plugin version)}
             (plugin hudson-plugins)
             (select-keys options [:url :md5]))
        hudson-data-path (parameter/get-for-target
                          session [:hudson :data-path])
        hudson-group (parameter/get-for-target
                      session [:hudson :group])]
    (-> session
        (directory/directory (str hudson-data-path "/plugins"))
        (thread-expr/apply->
         remote-file/remote-file
         (str hudson-data-path "/plugins/" (name plugin) ".hpi")
         :group hudson-group :mode "0664"
         (apply concat src))
        (plugin-config plugin options))))


(defn determine-scm-type
  "determine the scm type"
  [scm-spec]
  (let [scm-path (first scm-spec)]
    (cond
     (.contains scm-path "git") :git
     (.contains scm-path "svn") :svn
     (or (.contains scm-path "cvs")
         (.contains scm-path "pserver")) :cvs
     (.contains scm-path "bk") :bitkeeper
     :else nil)))

(defmulti output-scm-for
  "Output the scm definition for specified type"
  (fn [scm-type session scm-path options] scm-type))

(enlive/deffragment branch-transform
  [branch]
  [:name]
  (xml/content branch))

(def class-for-scm-remote
  {:git "org.spearce.jgit.transport.RemoteConfig"})

;; "Generate git scm configuration for job content"
(enlive/defsnippet git-job-xml
  (path-for *git-file*) session
  [session scm-path options]
  [:branches :> :*]
  (xml/clone-for [branch (:branches options ["*"])]
                 (branch-transform branch))
  [:mergeOptions]
  (let [target (:merge-target options)]
    (if target
      (xml/transformation
       [:mergeTarget] (xml/content target)
       [:mergeRemote] (xml/set-attr
                       :reference (format
                                   "../../remoteRepositories/%s"
                                   (class-for-scm-remote :git))))
      (xml/content "")))

  [:#url]
  (xml/do->
   (xml/content scm-path)
   (xml/remove-attr :id))
  [:#refspec]
  (xml/do->
   (xml/remove-attr :id)
   (enlive/transform-if-let [refspec (options :refspec)]
                            (xml/content refspec)))
  [:#receivepack]
  (xml/do->
   (xml/remove-attr :id)
   (enlive/transform-if-let [receivepack (options :receivepack)]
                            (xml/content receivepack)))
  [:#uploadpack]
  (xml/do->
   (xml/remove-attr :id)
   (enlive/transform-if-let [upload-pack (options :uploadpack)]
                            (xml/content upload-pack)))
  [:#tagopt]
  (xml/do->
   (xml/remove-attr :id)
   (enlive/transform-if-let [tagopt (options :tagopt)]
                            (xml/content tagopt))))

(defmethod output-scm-for :git
  [scm-type session scm-path options]
  (git-job-xml session scm-path options))

;; "Generate svn scm configuration for job content"
(enlive/defsnippet svn-job-xml
  (path-for *svn-file*) session
  [session scm-path options]
  [:locations :*] (xml/clone-for
                   [path (:branches options [""])]
                   [:remote] (xml/content (str (first scm-path) path)))
  [:useUpdate] (xml/content (truefalse (:use-update options)))
  [:doRevert] (xml/content (truefalse (:do-revert options)))
  ;; :browser {:class "a.b.c" :url "http://..."}
  [:browser] (if-let [browser (:browser options)]
               (xml/do->
                (xml/set-attr :class (:class browser))
                (xml/content
                 (map
                  #(hash-map :tag (key %) :content (val %))
                  (dissoc browser :class)))))
  [:excludedRegions] (xml/content (:excluded-regions options))
  [:includedRegions] (xml/content (:included-regions options))
  [:excludedUsers] (xml/content (:excluded-users options))
  [:excludedRevprop] (xml/content (:excluded-revprop options))
  [:excludedCommitMessages] (xml/content (:excluded-commit-essages options)))

(defmethod output-scm-for :svn
  [scm-type session scm-path options]
  (svn-job-xml session scm-path options))

(defn normalise-scms [scms]
  (map #(if (string? %) [%] %) scms))

(def class-for-scm
  {:git "hudson.plugins.git.GitSCM"
   :svn "hudson.scm.SubversionSCM"})

(def trigger-tags
  {:scm-trigger "hudson.triggers.SCMTrigger"
   :startup-trigger
   "org.jvnet.hudson.plugins.triggers.startup.HudsonStartupTrigger"})

(defmulti trigger-config
  "trigger configuration"
  (fn [[trigger options]] trigger))

(defmethod trigger-config :default
  [[trigger options]]
  (with-out-str
    (prxml/prxml [(keyword (trigger-tags trigger)) {} [:spec {} options]])))

(defmulti publisher-config
  "Publisher configuration"
  (fn [[publisher options]] publisher))

(def imstrategy {:all "ALL"})

(defmethod publisher-config :ircbot
  [[_ options]]
  (with-out-str
    (prxml/prxml
     [:hudson.plugins.ircbot.IrcPublisher {}
      [:targets {}
       [:hudson.plugins.im.GroupChatIMMessageTarget {}
        (map #(prxml/prxml
               [:name {} (:name %)]
               [:password {} (:password %)])
             (:targets options))]]
      [:strategy {:class "hudson.plugins.im.NotificationStrategy"}
       (imstrategy (:strategy options :all))]
      [:notifyOnBuildStart {}
       (if (:notify-on-build-start options) "true" false)]
      [:notifySuspects {}
       (if (:notify-suspects options) "true" false)]
      [:notifyCulprits {}
       (if (:notify-culprits options) "true" false)]
      [:notifyFixers {}
       (if (:notify-fixers options) "true" false)]
      [:notifyUpstreamCommitters {}
       (if (:notify-upstream-committers options) "true" false)]
      [:channels {}]])))

(defmethod publisher-config :artifact-archiver
  [[_ options]]
  (with-out-str
    (prxml/prxml [:hudson.tasks.ArtifactArchiver {}
            [:artifacts {} (:artifacts options)]
            [:latestOnly {} (truefalse (:latest-only options false))]])))

(def
  ^{:doc "Provides a map from stability to name, ordinal and color"}
  threshold-levels
  {:success {:name "SUCCESS" :ordinal 0 :color "BLUE"}
   :unstable {:name "UNSTABLE" :ordinal 1 :color "YELLOW"}})

(defmethod publisher-config :build-trigger
  [[_ options]]
  (let [threshold (threshold-levels (:threshold options :success))]
    (with-out-str
      (prxml/prxml
       [:hudson.tasks.BuildTrigger {}
        [:childProjects {} (:child-projects options)]
        [:threshold {}
         [:name {} (:name threshold)]
         [:ordinal {} (:ordinal threshold)]
         [:color {} (:color threshold)]]]))))

;; todo
;; -    <authorOrCommitter>false</authorOrCommitter>
;; -    <clean>false</clean>
;; -    <wipeOutWorkspace>false</wipeOutWorkspace>
;; -    <buildChooser class="hudson.plugins.git.util.DefaultBuildChooser"/>
;; -    <gitTool>Default</gitTool>
;; -    <submoduleCfg class="list"/>
;; -    <relativeTargetDir></relativeTargetDir>
;; -    <excludedRegions></excludedRegions>
;; -    <excludedUsers></excludedUsers>
(defn maven2-job-xml
  "Generate maven2 job/config.xml content"
  [session scm-type scms options]
  (enlive/xml-emit
   (enlive/xml-template
    (path-for *maven2-job-config-file*) session
    [scm-type scms options]
    [:daysToKeep] (enlive/transform-if-let [keep (:days-to-keep options)]
                                           (xml/content (str keep)))
    [:numToKeep] (enlive/transform-if-let [keep (:num-to-keep options)]
                                           (xml/content (str keep)))
    [:properties] (enlive/transform-if-let
                   [properties (:properties options)]
                   (xml/content (map plugin-property properties)))
    [:scm] (xml/substitute
            (when scm-type
              (output-scm-for scm-type session (first scms) options)))
    [:mavenName]
    (enlive/transform-if-let [maven-name (:maven-name options)]
                             (xml/content maven-name))
    [:jdk] (if-let [jdk (:jdk options)] (xml/content jdk))
    [:concurrentBuild] (xml/content
                        (truefalse (:concurrent-build options false)))
    [:goals]
    (enlive/transform-if-let [goals (:goals options)]
                             (xml/content goals))
    [:defaultGoals]
    (enlive/transform-if-let [default-goals (:default-goals options)]
                             (xml/content default-goals))
    [:mavenOpts]
    (enlive/transform-if-let [maven-opts (:maven-opts options)]
                             (xml/content
                              maven-opts))
    [:groupId]
    (enlive/transform-if-let [group-id (:group-id options)]
                             (xml/content group-id))
    [:artifactId]
    (enlive/transform-if-let [artifact-id (:artifact-id options)]
                             (xml/content artifact-id))
    [:properties :* :projectUrl]
    (enlive/transform-if-let [github-url (-> options :github :projectUrl)]
                             (xml/content github-url))

    [:authToken] (if-let [token (:auth-token options)]
                   (xml/content token))
    [:publishers]
    (xml/html-content
     (string/join (map publisher-config (:publishers options))))
    [:aggregatorStyleBuild] (xml/content
                             (truefalse
                              (:aggregator-style-build options true)))
    [:ignoreUpstreamChanges] (xml/content
                              (truefalse
                               (:ignore-upstream-changes options true)))
    [:triggers]
    (xml/html-content (string/join (map trigger-config (:triggers options)))))
   scm-type scms options))

(defn ant-job-xml
  "Generate ant job/config.xml content"
  [session scm-type scms options]
  (enlive/xml-emit
   (enlive/xml-template
    (path-for *ant-job-config-file*) session
    [scm-type scms options]
    [:daysToKeep] (enlive/transform-if-let [keep (:days-to-keep options)]
                                           (xml/content (str keep)))
    [:numToKeep] (enlive/transform-if-let [keep (:num-to-keep options)]
                                          (xml/content (str keep)))
    [:properties] (enlive/transform-if-let
                   [properties (:properties options)]
                   (xml/content (map plugin-property properties)))
    [:scm] (xml/substitute
            (when scm-type
              (output-scm-for scm-type session (first scms) options)))
    [:concurrentBuild] (xml/content
                        (truefalse (:concurrent-build options false)))
    [:builders xml/first-child] (xml/clone-for
                                 [task (:ant-tasks options)]
                                 [:targets] (xml/content (:targets task))
                                 [:antName] (xml/content (:ant-name task))
                                 [:buildFile] (xml/content
                                               (:build-file task))
                                 [:properties] (xml/content
                                                (format/name-values
                                                 (:properties task)
                                                 :separator "=")))
    [:publishers]
    (xml/html-content
     (string/join (map publisher-config (:publishers options))))
    [:aggregatorStyleBuild] (xml/content
                             (truefalse
                              (:aggregator-style-build options true)))
    [:ignoreUpstreamChanges] (xml/content
                              (truefalse
                               (:ignore-upstream-changes options true))))
   scm-type scms options))

(defmulti output-build-for
  "Output the build definition for specified type"
  (fn [build-type session scm-type scms options] build-type))

(defmethod output-build-for :maven2
  [build-type session scm-type scms options]
  (let [scm-type (or scm-type (some determine-scm-type scms))]
    (maven2-job-xml session scm-type scms options)))

(defmethod output-build-for :ant
  [build-type session scm-type scms options]
  (let [scm-type (or scm-type (some determine-scm-type scms))]
    (ant-job-xml session scm-type scms options)))

(defn credential-entry
  "Produce an xml representation for a credential entry in a credential store"
  [[name {:keys [user-name password]}]]
  [:entry {}
   [:string {} name]
   [:hudson.scm.SubversionSCM_-DescriptorImpl_-PasswordCredential {}
    [:userName {} user-name]
    [:password {} (Base64/encodeBase64String (.getBytes password))]]])

(defn credential-store
  "Output a credential store definition for a job configuration.
   Accepts a credential map from name to credentials. Credentials
   are a map containing :user-name and :password keys."
  [credential-map]
  (with-out-str
    (prxml/prxml
     [:decl! {:version "1.0"}]
     [:hudson.scm.PerJobCredentialStore {}
      [:credentials {:class "hashtable"}
       (map credential-entry credential-map)]])))

(defn url-without-path
  [url-string]
  (let [url (java.net.URL. url-string)]
    (java.net.URL. (.getProtocol url) (.getHost url) (.getPort url) "")))

(defn job
  "Configure a hudson job.

   `build-type` :maven2 is the only supported type at the moment.
   `name` - name to be used in links

   Options are:
   - :scm-type  determine scm type, eg. :git
   - :scm a sequence strings specifying scm locations.
   - :description \"a descriptive string\"
   - :branches [\"branch1\" \"branch2\"]
   - :properties specifies plugin properties, map from plugin keyword to a map
                 of tag values. Use :authorization-matrix to specify a sequence
                 of maps with :user and :permissions keys.
   - :publishers specifies a map of publisher specfic options
   Other options are SCM specific.

   git:
   - :name
   - :refspec
   - :receivepack
   - :uploadpack
   - :tagopt

   svn:
   - :use-update
   - :do-revert
   - :browser {:class \"a.b.c\" :url \"http://...\"}
   - :excluded-regions
   - :included-regions
   - :excluded-users
   - :excluded-revprop
   - :excludedCommitMessages


   Example
       (job
         :maven2 \"project\"
         :maven-opts \"-Dx=y\"
         :branches [\"origin/master\"]
         :scm [\"http://project.org/project.git\"]
         :num-to-keep 10
         :browser {:class \"hudson.scm.browsers.FishEyeSVN\"
                   :url \"http://fisheye/browse/\"}
         :concurrent-build true
         :goals \"clean install\"
         :default-goals \"install\"
         :ignore-upstream-changes true
         :properties {:disk-usage {}
                      :authorization-matrix
                        [{:user \"anonymous\"
                          :permissions #{:item-read :item-build}}]}
         :publishers {:artifact-archiver
                       {:artifacts \"**/*.war\" :latestOnly false}})"
  [session build-type job-name & {:keys [refspec receivepack uploadpack
                                         tagopt description branches scm
                                         scm-type merge-target
                                         subversion-credentials]
                                  :as options}]
  (let [hudson-owner (parameter/get-for-target session [:hudson :owner])
        hudson-group (parameter/get-for-target session [:hudson :group])
        hudson-data-path (parameter/get-for-target
                          session [:hudson :data-path])
        scm (normalise-scms (:scm options))]
    (logging/trace (str "Hudson - configure job " job-name))
    (->
     session
     (directory/directory (str hudson-data-path "/jobs/" job-name) :p true
                :owner hudson-owner :group hudson-group :mode  "0775")
     (remote-file/remote-file
      (str hudson-data-path "/jobs/" job-name "/config.xml")
      :content
      (output-build-for
       build-type
       session
       (:scm-type options)
       scm
       (dissoc options :scm :scm-type))
      :owner hudson-owner :group hudson-group :mode "0664")
     (directory/directory
      hudson-data-path
      :owner hudson-owner :group hudson-group
      :mode "g+w"
      :recursive true)
     (thread-expr/when->
      subversion-credentials
      (remote-file/remote-file
      (str hudson-data-path "/jobs/" job-name "/subversion.credentials")
      :content
      (credential-store (zipmap
                         (map #(str "<" (url-without-path (ffirst scm)) ">" %)
                              (keys subversion-credentials))
                         (vals subversion-credentials)))
      :owner hudson-owner :group hudson-group :mode "0664")))))



(enlive/deffragment hudson-task-transform
  [name version]
  [:name]
  (xml/content name)
  [:id]
  (xml/content version))


(defn- hudson-tool-path
  [hudson-data-path name]
  (str hudson-data-path "/tools/" (string/replace name #" " "_")))

(defn hudson-maven-xml
  "Generate hudson.task.Maven.xml content"
  [session hudson-data-path maven-tasks]
  (enlive/xml-emit
   (enlive/xml-template
    (path-for *maven-file*) session
    [tasks]
    [:installations xml/first-child]
    (xml/clone-for [task tasks]
                   [:name] (xml/content (first task))
                   [:home] (xml/content
                            (hudson-tool-path hudson-data-path (first task)))
                   [:properties] nil))
   maven-tasks))


(def remote-file* (action/action-fn remote-file/remote-file-action))
(def directory* (action/action-fn directory/directory))

;; todo: merge with hudson-maven-xml
;; maven should look more like this - with the automated naming etc
;; at a higher level.
(defn hudson-tool-install-xml
  [name path properties]
  (xml/transformation
   [:name] (xml/content name)
   [:home] (xml/content path)
   [:properties] (xml/content (format/name-values properties :separator "="))))

(defn hudson-ant-xml
  "Generate hudson.task.Ant.xml content"
  [node-type hudson-data-path installations]
  (enlive/xml-emit
   (enlive/xml-template
    (path-for *ant-file*) node-type
    [installs]
    [:installations xml/first-child] (xml/clone-for
                                      [[name path properties] installs]
                                      (hudson-tool-install-xml
                                       name path properties)))
   installations))

(action/def-collected-action maven-config
  "Configure a maven instance for hudson."
  {:arglists '([session name version])}
  [session args]
  (let [group (parameter/get-for-target session [:hudson :group])
        hudson-owner (parameter/get-for-target session [:hudson :owner])
        user (parameter/get-for-target session [:hudson :user])
        hudson-data-path (parameter/get-for-target
                          session [:hudson :data-path])]
    (stevedore/do-script
     (directory* session "/usr/share/tomcat6/.m2" :group group :mode "g+w")
     (directory*
      session hudson-data-path :owner hudson-owner :group group :mode "775")
     (remote-file*
      session
      (str hudson-data-path "/" *maven-file*)
      :content (apply
                str (hudson-maven-xml session hudson-data-path args))
      :owner hudson-owner :group group :mode "0664"))))

(action/def-collected-action ant-config
  "Configure an ant tool installation descriptor for hudson.
   - `name`        a name for this installation of ant
   - `path`        a path to the home of this ant installation
   - `properties`  a properties map for this installation of ant"
  {:arglists '([session name path properties])}
  [session args]
  (let [group (parameter/get-for-target session [:hudson :group])
        hudson-owner (parameter/get-for-target session [:hudson :owner])
        user (parameter/get-for-target session [:hudson :user])
        hudson-data-path (parameter/get-for-target
                          session [:hudson :data-path])]
    (stevedore/do-script
     (directory*
      session hudson-data-path :owner hudson-owner :group group :mode "775")
     (remote-file*
      session
      (str hudson-data-path "/" *ant-file*)
      :content (apply
                str (hudson-ant-xml session hudson-data-path args))
      :owner hudson-owner :group group :mode "0664"))))

(defn maven
  [session name version]
  (let [group (parameter/get-for-target session [:hudson :group])
        hudson-owner (parameter/get-for-target session [:hudson :owner])
        hudson-data-path (parameter/get-for-target
                          session [:hudson :data-path])]
    (->
     session
     (maven/download
      :maven-home (hudson-tool-path hudson-data-path name)
      :version version :owner hudson-owner :group group)
     (maven-config name version))))

(defn ant
  "Install and use ant with hudson"
  [request name version]
  (let [group (parameter/get-for-target request [:hudson :group])
        hudson-owner (parameter/get-for-target request [:hudson :owner])
        hudson-data-path (parameter/get-for-target
                          request [:hudson :data-path])]
    (->
     request
     #_(ant/download
      :ant-home (hudson-tool-path hudson-data-path name)
      :version version :owner hudson-owner :group group)
     (ant-config name (hudson-tool-path hudson-data-path name) nil))))

(defn hudson-user-xml
  "Generate user config.xml content"
  [session user]
  (enlive/xml-emit
   (enlive/xml-template
    (path-for *user-config-file*) session
    [user]
    [:fullName] (xml/content (:full-name user))
    [(xml/tag= "hudson.security.HudsonPrivateSecurityRealm_-Details")
     :passwordHash]
    (:password-hash user)
    [(xml/tag= "hudson.tasks.Mailer_-UserProperty") :emailAddress]
    (:email user))
   user))

(defn user
  "Add a hudson user, using hudson's user database."
  [session username {:keys [full-name password-hash email] :as user}]
  (let [group (parameter/get-for-target session [:hudson :group])
        hudson-owner (parameter/get-for-target session [:hudson :owner])
        hudson-data-path (parameter/get-for-target
                          session [:hudson :data-path])]
    (-> session
        (directory/directory
         (format "%s/users/%s" hudson-data-path username)
         :owner hudson-owner :group group :mode "0775")
        (remote-file/remote-file
         (format "%s/users/%s/config.xml" hudson-data-path username)
         :content (hudson-user-xml session user)
         :owner hudson-owner :group group :mode "0664"))))


(defn config-xml
  "Generate config.xml content"
  [session options]
  (enlive/xml-emit
   (enlive/xml-template
    (path-for *config-file*) session
    [options]
    [:useSecurity] (xml/content (if (:use-security options) "true" "false"))
    [:securityRealm] (when-let [realm (:security-realm options)]
                       (xml/set-attr :class (security-realm-class realm)))
    [:disableSignup] (xml/content
                      (if (:disable-signup options) "true" "false"))
    [:authorizationStrategy] (when-let [strategy (:authorization-strategy
                                                  options)]
                               (xml/set-attr
                                :class (authorization-strategy-class strategy)))
    [:permission] (xml/clone-for
                   [permission (apply
                                concat
                                (map
                                 (fn user-perm [user-permissions]
                                   (map
                                    #(hash-map
                                      :user (:user user-permissions)
                                      :permission (permission-class % %))
                                    (:permissions user-permissions)))
                                 (:permissions options)))]
                   (xml/content
                    (format "%s:%s"
                            (:permission permission) (:user permission)))))
   options))

(defn config
  "hudson config."
  [session & {:keys [use-security security-realm disable-signup
                     admin-user admin-password] :as options}]
  (let [group (parameter/get-for-target session [:hudson :group])
        hudson-owner (parameter/get-for-target session [:hudson :owner])
        hudson-data-path (parameter/get-for-target
                          session [:hudson :data-path])]
    (-> session
        (parameter/assoc-for-target
         [:hudson :admin-user] admin-user
         [:hudson :admin-password] admin-password)
        (remote-file/remote-file
         (format "%s/config.xml" hudson-data-path)
         :content (config-xml session options)
         :owner hudson-owner :group group :mode "0664"))))
