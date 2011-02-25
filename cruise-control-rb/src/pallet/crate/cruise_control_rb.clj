(ns pallet.crate.cruise-control-rb
 "Installation cruise-control.rb"
 (:require
  [pallet.action :as action]
  [pallet.action.directory :as directory]
  [pallet.action.exec-script :as exec-script]
  [pallet.action.file :as file]
  [pallet.action.remote-file :as remote-file]
  [pallet.action.service :as service]
  [pallet.action.user :as user]
  [pallet.argument :as argument]
  [pallet.stevedore :as stevedore]
  [pallet.template :as template]
  [pallet.utils :as utils]))

(def cruise-control-rb-downloads
     {"1.4.0" [ "http://rubyforge.org/frs/download.php/59598/cruisecontrol-1.4.0.tgz"
                "772d8300cc61a581f55e9f1e33eacb6b"]})

(def cruise-control-rb-install "/opt/cruisecontrolrb")
(def cruise-control-rb-data "/var/lib/cruisecontrolrb")
(def cruise-control-rb-user "ccrb")
(def cruise-control-rb-owenr cruise-control-rb-user)
(def cruise-control-rb-group "root")
(def cruise-control-rb-init-script "crate/cruise-control-rb/cruisecontrol.rb")

(defn determine-scm-type
  "determine the scm type"
  [scm-path]
  (cond
   (.contains scm-path "git") :git
   (.contains scm-path "svn") :svn
   (or (.contains scm-path "cvs")
       (.contains scm-path "pserver")) :cvs
   (.contains scm-path "bk") :bitkeeper
   :else nil))

(defn cruise-control-rb
  "Downloads and installs the specified version of cruisecontrol.rb.  Creates a
cruiscontrol.rb init script."
  ([session] (cruise-control-rb session "1.4.0"))
  ([session version]
     (let [info (cruise-control-rb-downloads version)
           basename (str "cruisecontrol-" version)
           tarfile (str basename ".tgz")
           tarpath (str (stevedore/script (tmp-dir)) "/" tarfile)]
       (-> session
           (remote-file/remote-file
            tarpath :url (first info) :md5 (second info))
           (user/user
            cruise-control-rb-user :home cruise-control-rb-data :shell :false)
           (directory/directory cruise-control-rb-install)
           (directory/directory
            cruise-control-rb-data :owner cruise-control-rb-user)
           (exec-script/exec-script
            (cd ~cruise-control-rb-install)
            (tar xz --strip-components=1 -f ~tarpath))
           (file/file
            (str cruise-control-rb-install "/public/stylesheets/site.css")
            :owner cruise-control-rb-user)
           (file/file
            (str cruise-control-rb-install "/log/production.log")
            :owner cruise-control-rb-user
            :mod "0664")
           (file/file
            (str cruise-control-rb-install "/log/add_project.log")
            :owner cruise-control-rb-user
            :mod "0664")
           (directory/directory
            (str cruise-control-rb-install "/tmp")
            :owner cruise-control-rb-user)
           (directory/directory
            (str cruise-control-rb-install "/tmp")
            :owner cruise-control-rb-user)))))

(defn cruise-control-rb-init
  "Creates a cruiscontrol.rb init script."
  [session]
  (service/init-script
   session
   "cruisecontrol.rb"
   :content (utils/load-resource-url
             (template/find-template cruise-control-rb-init-script session))
   :literal true))

(defn- project-path [project]
  (str cruise-control-rb-data "/projects/" project))

(defn cruise-control-rb-job
  "Add a cruise control.rb job.
Options include:
  :branch name   -- specify the branch"
  [session name repository-url
   & {:keys [action branch] :or {action :create} :as options}]
  (case action
    :create (exec-script/exec-checked-script
             session
             "cruise-control-rb"
             (export ~(format "CRUISE_DATA_ROOT=%s" cruise-control-rb-data))
             (if-not (file-exists? ~(project-path name))
               (sudo
                -u ~cruise-control-rb-user
                ~(format "CRUISE_DATA_ROOT=%s" cruise-control-rb-data)
                ~(str cruise-control-rb-install "/cruise")
                add ~name
                --repository ~repository-url
                --source-control ~(determine-scm-type repository-url)
                ~(stevedore/option-args
                  (apply concat (dissoc options :action))))))
    :remove (directory/directory
             session
             (project-path name)
             :action :remove :recursive true :force true)))
