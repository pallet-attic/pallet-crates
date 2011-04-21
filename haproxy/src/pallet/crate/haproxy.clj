(ns pallet.crate.haproxy
  "HA Proxy installation and configuration"
  (:require
   [pallet.argument :as argument]
   [pallet.compute :as compute]
   [pallet.parameter :as parameter]
   [pallet.session :as session]
   [pallet.action :as action]
   [pallet.action.package :as package]
   [pallet.action.package.epel :as epel]
   [pallet.action.remote-file :as remote-file]
   [pallet.crate.etc-default :as etc-default]
   [clojure.contrib.logging :as logging]
   [clojure.string :as string]
   clojure.set)
  (:use
   [clojure.contrib.core :only [-?>]]
   pallet.thread-expr))

(def conf-path "/etc/haproxy/haproxy.cfg")

(def haproxy-user "haproxy")
(def haproxy-group "haproxy")

(def default-global
  {:log ["127.0.0.1 local0" "127.0.0.1 local1 notice"]
   :maxconn 4096
   :user "haproxy"
   :group "haproxy"
   :daemon true})

(def default-defaults
  {:log "global"
   :mode "http"
   :option ["httplog" "dontlognull" "redispatch"]
   :retries 3
   :maxconn 2000
   :contimeout 5000
   :clitimeout 50000
   :srvtimeout 50000})

(defn install-package
  "Install HAProxy from packages"
  [session]
  (logging/debug (format "INSTALL-HAPROXY %s" (session/os-family session)))
  (-> session
      (when->
       (#{:amzn-linux :centos} (session/os-family session))
       (epel/add-epel :version "5-4"))
      (package/package "haproxy")))

(defmulti format-kv (fn format-kv-dispatch [k v & _] (class v)))

(defmethod format-kv :default
  [k v sep]
  (format "%s %s%s" (name k) v sep))

(defmethod format-kv clojure.lang.IPersistentVector
  [k v sep]
  (reduce (fn format-kv-vector [s value] (str s (format-kv k value sep))) "" v))

(defmethod format-kv clojure.lang.Sequential
  [k v sep]
  (reduce (fn format-kv-vector [s value] (str s (format-kv k value sep))) "" v))

(defmethod format-kv Boolean
  [k v sep]
  (when v (format "%s%s" (name k) sep)))

(defn- config-values
  "Format a map as key value pairs"
  [m]
  (apply str (for [[k v] m] (format-kv k v \newline))))

(defn- config-section
  [[key values]]
  (if (= :listen key)
    (reduce
     #(str
       %1
       (format
        "%s %s %s\n%s"
        (name key) (name (first %2)) (:server-address (second %2))
        (config-values (dissoc (second %2) :server-address))))
     ""
     values)
    (format "%s\n%s" (name key) (config-values values))))

(defn- config-server
  "Format a server configuration line"
  [m]
  {:pre [(:name m) (:ip m)]}
  (format
   "%s %s%s %s"
   (name (:name m))
   (:ip m)
   (if-let [p (:server-port m)] (str ":" p) "")
   (apply
    str
    (for [[k v] (dissoc m :server-port :ip :name)]
      (format-kv k v " ")))))

(defn merge-servers
  [session options]
  (let [options (update-in
                 options [:listen]
                 (fn [m]
                   (zipmap (map keyword (keys m)) (vals m))))
        apps (map keyword (keys (:listen options)))
        group-name (keyword (session/group-name session))
        srv-apps (-?> session :parameters :haproxy group-name)
        app-keys (keys srv-apps)
        unconfigured (clojure.set/difference (set app-keys) (set apps))
        no-nodes (clojure.set/difference (set app-keys) (set apps))]
    (when (seq unconfigured)
      (doseq [app unconfigured]
        (logging/warn
         (format
          "Unconfigured proxy %s %s"
          group-name app))))
    (when (seq no-nodes)
      (doseq [app no-nodes]
        (logging/warn
         (format
          "Configured proxy %s %s with no servers"
          group-name app))))
    (reduce
     #(update-in %1 [:listen (keyword (first %2)) :server]
                 (fn [servers]
                   (concat
                    (or servers [])
                    (map config-server (second %2)))))
     options
     srv-apps)))

(defn configure
  "Configure HAProxy.
   :global and :defaults both take maps of keyword value pairs. :listen takes a
   map where the keys are of the form \"name\" and contain an :server-address
   key with a string containing ip:port, and other keyword/value. Servers for
   each listen section can be declared with the proxied-by function."
  [session
   & {:keys [global defaults listen frontend backend]
              :as options}]
  (->
   session
   (remote-file/remote-file
    conf-path
    :content (argument/delayed
              [session]
              (let [combined (merge
                              {:global default-global
                               :defaults default-defaults}
                              (merge-servers session options))]
                (string/join
                 (map
                  config-section
                  (map
                   (juxt identity combined)
                   (filter
                    combined
                    [:global :defaults :listen :frontend :backend]))))))
    :literal true)
   (etc-default/write "haproxy" :ENABLED 1)))


(defn proxied-by
  "Declare that a node is proxied by the given haproxy server.

   (proxied-by session :haproxy :app1 :check true)."
  [session proxy-group-name proxy-group
   & {:keys [server-port addr backup check cookie disabled fall id
             inter fastinter downinter maxqueue minconn port redir
             rise slowstart source track weight]
      :as options}]
  (->
   session
   (parameter/update-for
    [:haproxy (keyword proxy-group-name) (keyword proxy-group)]
    (fn [v]
      (conj
       (or v [])
       (merge
        options
        {:ip (session/target-ip session)
         :name (session/safe-name session)}))))))

#_
(pallet.core/defnode haproxy
  {}
  :bootstrap (pallet.phase/phase-fn
              (pallet.crate.automated-admin-user/automated-admin-user))
  :configure (pallet.phase/phase-fn
              (pallet.crate.haproxy/install-package)
              (pallet.crate.haproxy/configure)))
