(ns pallet.crate.zookeeper
  (:require
   [pallet.action.directory :as directory]
   [pallet.action.file :as file]
   [pallet.action.remote-directory :as remote-directory]
   [pallet.action.remote-file :as remote-file]
   [pallet.action.service :as service]
   [pallet.action.user :as user]
   [pallet.argument :as argument]
   [pallet.compute :as compute]
   [pallet.parameter :as parameter]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore]
   [clojure.string :as string])
  (:use
   pallet.thread-expr))

(def install-path "/usr/local/zookeeper")
(def log-path "/var/log/zookeeper")
(def tx-log-path (format "%s/txlog" log-path))
(def config-path "/etc/zookeeper")
(def data-path "/var/zookeeper")
(def zookeeper-home install-path)
(def zookeeper-user "zookeeper")
(def zookeeper-group "zookeeper")
(def default-config
  {:dataDir data-path
   :tickTime 2000
   :clientPort 2181
   :initLimit 10
   :syncLimit 5
   :dataLogDir tx-log-path})


(defn url "Download url"
  [version]
  (format
   "http://www.apache.org/dist/hadoop/zookeeper/zookeeper-%s/zookeeper-%s.tar.gz"
   version version))

(defn install
  "Install Zookeeper"
  [session & {:keys [user group version home]
              :or {user zookeeper-user
                   group zookeeper-group
                   version "3.3.1"}
              :as options}]
  (let [url (url version)
        home (or home (format "%s-%s" install-path version))]
    (->
     session
     (parameter/assoc-for
      [:zookeeper :home] home
      [:zookeeper :owner] user
      [:zookeeper :group] group)
     (user/user user :system true)
     (user/group group :system true)
     (remote-directory/remote-directory
      home
      :url url :md5-url (str url ".md5")
      :unpack :tar :tar-options "xz"
      :owner user :group group)
     (directory/directory log-path :owner user :group group :mode "0755")
     (directory/directory tx-log-path :owner user :group group :mode "0755")
     (directory/directory config-path :owner user :group group :mode "0755")
     (directory/directory data-path :owner user :group group :mode "0755")
     (remote-file/remote-file
      (format "%s/log4j.properties" config-path)
      :remote-file (format "%s/conf/log4j.properties" home)
      :owner user :group group :mode "0644")
     (file/sed
      (format "%s/log4j.properties" config-path)
      {"log4j.rootLogger=INFO, CONSOLE"
       "log4j.rootLogger=INFO, ROLLINGFILE"
       "log4j.appender.ROLLINGFILE.File=zookeeper.log"
       (format "log4j.appender.ROLLINGFILE.File=%s/zookeeper.log" log-path)}
      :seperator "|"))))

(defn init
  [session & {:as options}]
  (->
   session
   (service/init-script
    "zookeeper"
    :link (format
           "%s/bin/zkServer.sh"
           (parameter/get-for session [:zookeeper :home])))
   (file/sed
    (format
     "%s/bin/zkServer.sh"
     (parameter/get-for session [:zookeeper :home]))
    {"# chkconfig:.*" ""
     "# description:.*" ""
     "# by default we allow local JMX connections"
     "# by default we allow local JMX connections\\n# chkconfig: 2345 20 80\\n# description: zookeeper"})
   (if-not-> (:no-enable options)
             (service/service
              "zookeeper" :action :start-stop
              :sequence-start "20 2 3 4 5"
              :sequence-stop "20 0 1 6"))))

(defn config-files
  "Create a zookeeper configuration file.  We sort by name to preserve sequence
   across invocations."
  [session]
  (let [target-name (session/target-name session)
        target-ip (session/target-ip session)
        nodes (sort-by compute/hostname (session/nodes-in-group session))
        configs (parameter/get-for
                 session
                 [:zookeper (keyword (session/group-name session))])
        config (configs (keyword target-name))
        owner (parameter/get-for session [:zookeeper :owner])
        group (parameter/get-for session [:zookeeper :group])]
    (->
     session
     (remote-file/remote-file
      (format "%s/zoo.cfg" config-path)
      :content (str (string/join
                     \newline
                     (map #(format "%s=%s" (name (first %)) (second %))
                          (merge
                           default-config
                           (dissoc config :electionPort :quorumPort))))
                    \newline
                    (when (> (count nodes) 1)
                      (string/join
                       \newline
                       (map #(let [config (configs
                                           (keyword (compute/hostname %1)))]
                               (format "server.%s=%s:%s:%s"
                                       %2
                                       (compute/private-ip %1)
                                       (:quorumPort config 2888)
                                       (:electionPort config 3888)))
                            nodes
                            (range 1 (inc (count nodes)))))))
      :owner owner :group group :mode "0644")

     (remote-file/remote-file
      (format "%s/myid" data-path)
      :content (str (some #(and (= target-ip (second %)) (first %))
                          (map #(vector %1 (compute/primary-ip %2))
                               (range 1 (inc (count nodes)))
                               nodes)))
      :owner owner :group group :mode "0644"))))

(defn store-configuration
  "Capture zookeeper configuration"
  [session options]
  (parameter/update-for
   session
   [:zookeper (keyword (session/group-name session))]
   (fn [m]
     (assoc m (session/target-name session) options))))

(defn configure
  "Configure zookeeper instance"
  [session & {:keys [dataDir tickTime clientPort initLimit syncLimit dataLogDir
                     electionPort quorumPort]
              :or {client-port 2181 quorumPort 2888 electionPort 3888}
              :as options}]
  (->
   session
   (store-configuration
    (assoc options :quorumPort quorumPort :electionPort electionPort))
   (config-files)))

#_
(pallet.core/defnode zk
  {}
  :bootstrap (pallet.action/phase
              (pallet.crate.automated-admin-user/automated-admin-user))
  :configure (pallet.action/phase
              (pallet.crate.java/java :openjdk :jdk)
              (pallet.crate.zookeeper/install)
              (pallet.crate.zookeeper/configure)
              (pallet.crate.zookeeper/init))
  :restart-zookeeper (pallet.action/phase
                      (pallet.action.service/service
                       "zookeeper" :action :restart)))
