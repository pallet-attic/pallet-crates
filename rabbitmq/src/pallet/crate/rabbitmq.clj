(ns pallet.crate.rabbitmq
  (:require
   [pallet.action.directory :as directory]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.package :as package]
   [pallet.action.remote-file :as remote-file]
   [pallet.compute :as compute]
   [pallet.crate.etc-default :as etc-default]
   [pallet.crate.etc-hosts :as etc-hosts]
   [pallet.crate.iptables :as iptables]
   [pallet.parameter :as parameter]
   [pallet.session :as session]
   [clojure.string :as string])
  (:use
   pallet.thread-expr))

(def ^{:doc "Settings for the daemon"}
  etc-default-keys
  {:node-count :NODE_COUNT
   :rotate-suffix :ROTATE_SUFFIX
   :user :USER
   :name :NAME
   :desc :DESC
   :init-log-dir :INIT_LOG_DIR
   :daemon :DAEMON})

(def ^{:doc "RabbitMQ conf settings"} conf-keys
  {:mnesia-base :MNESIA_BASE
   :log-base :LOG_BASE
   :nodename :NODENAME
   :node-ip-addres :NODE_IP_ADDRESS
   :node-port :NODE_PORT
   :config-file :CONFIG_FILE
   :server-start-args :SERVER_START_ARGS
   :multi-start-args :MULTI_START_ARGS
   :ctl-erl-args :CTL_ERL_ARGS})

(defmulti erlang-config-format class)
(defmethod erlang-config-format :default
  [x]
  (str x))

(defmethod erlang-config-format clojure.lang.Named
  [x]
  (name x))

(defmethod erlang-config-format java.lang.String
  [x]
  (str "'" x "'"))

(defmethod erlang-config-format java.util.Map$Entry
  [x]
  (str
   "{" (erlang-config-format (key x)) ", " (erlang-config-format (val x)) "}"))

(defmethod erlang-config-format clojure.lang.ISeq
  [x]
  (str "[" (string/join "," (map erlang-config-format x)) "]"))

(defmethod erlang-config-format clojure.lang.IPersistentMap
  [x]
  (str "[" (string/join "," (map erlang-config-format x)) "]"))

(defn erlang-config [m]
  (str (erlang-config-format m) "."))

(defn- cluster-nodes
  "Create a node list for the specified nodes"
  [node-name nodes]
  (map
   (fn cluster-node-name [node]
     (str node-name "@" (compute/hostname node)))
   nodes))

(defn- cluster-nodes-for-group
  "Create a node list for the specified group"
  [session group]
  (let [nodes (session/nodes-in-group session group)]
    (assert (seq nodes))
    (cluster-nodes
     (parameter/get-for
      session
      [:host (keyword (compute/id (first nodes))) :rabbitmq :options :node-name]
      "rabbit")
     nodes)))

(defn- default-cluster-nodes
  [session options]
  (cluster-nodes
   (:node-name options "rabbit")
   (session/nodes-in-group session)))

(defn- configure
  "Write the configuration file, based on a hash map m, that is serialised as
   erlang config.  By specifying :cluster group, the current group's rabbitmq
   instances will be added as ram nodes to that cluster."
  [session cluster config]
  (let [options (parameter/get-for-target session [:rabbitmq :options] nil)
        cluster-nodes (when cluster (cluster-nodes-for-group session cluster))
        cluster-nodes (or cluster-nodes
                          (if-let [node-count (:node-count options)]
                            (when (> node-count 1)
                              (default-cluster-nodes session options))))]
    (->
     session
     (etc-hosts/hosts-for-group (session/group-name session))
     (when->
      (or cluster-nodes config)
      (remote-file/remote-file
       (parameter/get-for-target session [:rabbitmq :config-file])
       :content (erlang-config
                 (if cluster-nodes
                   (assoc-in config [:rabbit :cluster_nodes] cluster-nodes)
                   config))
       :literal true))
     (when->
      cluster
      (etc-hosts/hosts-for-group cluster)))))

(defn rabbitmq
  "Install rabbitmq from packages.
    :config map   - erlang configuration, specified as a map
                    from application to attribute value map.
    :cluster group  - If specified, then this group will be ram nodes for the
                    given group's disk cluster."
  [session & {:keys [node node-count mnesia-base log-base node-ip-address
                     node-port config-file config cluster]
              :as options}]
  (->
   session
   (parameter/assoc-for-target
    [:rabbitmq :options] options
    [:rabbitmq :default-file] (or config-file "/etc/default/rabbitmq")
    [:rabbitmq :conf-file] (or config-file "/etc/rabbitmq/rabbitmq.conf")
    [:rabbitmq :config-file] (or config-file "/etc/rabbitmq/rabbitmq.config"))
   (directory/directory
    "/etc/rabbitmq")
   (apply-map->
    etc-default/write "rabbitmq"
    (map #(vector (etc-default-keys (first %)) (second %))
         (select-keys options (keys etc-default-keys))))
   (apply-map->
    etc-default/write "/etc/rabbitmq/rabbitmq.conf"
    (map #(vector (conf-keys (first %)) (second %))
         (select-keys options (keys conf-keys))))
   (package/package "rabbitmq-server")
   (configure cluster config)
   (etc-hosts/hosts)))

(defn iptables-accept
  "Accept rabbitmq connectios, by default on port 5672"
  ([session] (iptables-accept session 5672))
  ([session port]
     (iptables/iptables-accept-port session port)))

(defn iptables-accept-status
  "Accept rabbitmq status connections, by default on port 55672"
  ([session] (iptables-accept session 55672))
  ([session port]
     (iptables/iptables-accept-port session port)))

(defn password
  "Change rabbitmq password."
  [session user password]
  (->
   session
   (exec-script/exec-checked-script
    "Change RabbitMQ password"
    (rabbitmqctl change_password ~user ~password))))

;; rabbitmq without iptablse
#_
(pallet.core/defnode a {}
  :bootstrap (pallet.phase/phase-fn
              (pallet.crate.automated-admin-user/automated-admin-user))
  :configure (pallet.phase/phase-fn
              (pallet.crate.rabbitmq/rabbitmq))
  :rabbitmq-restart (pallet.phase/phase-fn
                     (pallet.action.service/service
                      "rabbitmq-server" :action :restart)))

;; cluster
#_
(pallet.core/defnode a {}
  :bootstrap (pallet.phase/phase-fn
              (pallet.crate.automated-admin-user/automated-admin-user))
  :configure (pallet.phase/phase-fn
              (pallet.crate.rabbitmq/rabbitmq))
  :rabbitmq-restart (pallet.phase/phase-fn
                     (pallet.action.service/service
                      "rabbitmq-server" :action :restart)))

#_
(pallet.core/defnode ram-nodes {}
  :bootstrap (pallet.phase/phase-fn
              (pallet.crate.automated-admin-user/automated-admin-user))
  :configure (pallet.phase/phase-fn
              (pallet.crate.rabbitmq/rabbitmq))
  :rabbitmq-restart (pallet.phase/phase-fn
                     (pallet.action.service/service
                      "rabbitmq-server" :action :restart)))

;; rabbitmq with iptables
#_
(pallet.core/defnode a {}
  :bootstrap (pallet.phase/phase-fn
              (pallet.crate.automated-admin-user/automated-admin-user))
  :configure (pallet.phase/phase-fn
              (pallet.crate.iptables/iptables-accept-icmp)
              (pallet.crate.iptables/iptables-accept-established)
              (pallet.crate.ssh/iptables-throttle)
              (pallet.crate.ssh/iptables-accept)
              (pallet.crate.rabbitmq/rabbitmq :node-count 2)
              (pallet.crate.rabbitmq/iptables-accept))
  :rabbitmq-restart (pallet.phase/phase-fn
                     (pallet.action.service/service
                      "rabbitmq-server" :action :restart)))
