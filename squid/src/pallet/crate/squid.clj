(ns pallet.crate.squid
  "Crate for Squid.

   Installation from package with the `squid` function. Top level configuration
   with `configure`.

   Directives are here:
     http://www1.at.squid-cache.org/Versions/v2/HEAD/cfgman/index.html"
  (:require
   [pallet.crate.iptables :as iptables]
   [pallet.compute :as compute]
   [pallet.parameter :as parameter]
   [pallet.request-map :as request-map]
   [pallet.resource :as resource]
   [pallet.resource.filesystem-layout :as filesystem-layout]
   [pallet.resource.package :as package]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.service :as service]
   [pallet.stevedore :as stevedore]
   [pallet.thread-expr :as thread-expr]
   [clojure.string :as string]))

(defn- default-config-home
  "Default directory for configuration files."
  []
  (stevedore/script (str (pkg-config-root) "/squid/")))

(def ^{:doc "Flag for recognising changes to configuration"}
  squid-config-changed-flag "squid-config")

(defn squid
  "Install squid from packages.
   options as for pallet.resource.package/package."
  [request & {:keys [action config-dir package-name service-name]
              :or {package-name "squid"
                   config-dir (default-config-home)}
              :as options}]
  (->
   request
   (package/package-manager :update)
   (thread-expr/apply-map-> package/package package-name options)
   (parameter/assoc-for-target
    [:squid :config-dir] config-dir
    [:squid :service] (or service-name package-name))))

(defn init-service
  "Control the squid service.

   Specify `:if-config-changed true` to make actions conditional on a change in
   configuration.

   Other options are as for `pallet.resource.service/service`. The service
   name is looked up in the request parameters."
  [request & {:keys [action if-config-changed if-flag] :as options}]
  (let [service (parameter/get-for-target request [:squid :service])
        options (if if-config-changed
                  (assoc options :if-flag squid-config-changed-flag)
                  options)]
    (-> request (thread-expr/apply-map-> service/service service options))))


(def default-config
  {:acl {:all "src 0.0.0.0/0.0.0.0"
         :manager "proto cache_object"
         :localhost "src 127.0.0.1/255.255.255.255"
         :to_localhost "dst 127.0.0.0/8"
         :SSL_ports "port 443"
         :safe_ports ["port 80"         ; http
                      "port 21"         ; ftp
                      "port 443"        ; https
                      "port 70"         ; gopher
                      "port 210"        ; wais
                      "port 1025-65535" ; unregistered ports
                      "port 280"        ; http-mgmt
                      "port 488"        ; gss-http
                      "port 591"        ; filemaker
                      "port 777"]       ; multiling http
         :CONNECT "method CONNECT"
         :QUERY "urlpath_regex cgi-bin \\?"
         :apache "rep_header Server ^Apache"
         :localnet ["src 10.0.0.0/8"     ; RFC 1918 possible internal network
                    "src 172.16.0.0/12"  ; RFC 1918 possible internal network
                    "src 192.168.0.0/16" ; RFC 1918 possible internal network
                    "src fc00::/7"       ; RFC 4193 local private network range
                    "src fe80::/10"]}   ; RFC 4291 link-local (directly plugged)
   :http_access ["allow manager localhost"
                 "deny manager"
                 "deny !safe_ports"
                 "deny CONNECT !SSL_ports"
                 "allow localhost"
                 "allow localnet"
                 "deny all"]
   :icp_access "allow all"
   :http_port 3128
   :hierarchy_stoplist ["cgi-bin" "?"]
   :access_log "/var/log/squid/access.log squid"
   :cache ["deny QUERY" "allow all"]
   :refresh_pattern {"^ftp:"    "1440    20%     10080"
                     "^gopher:" "1440    0%      1440"
                     "."        "0       20%     4320"}
   :broken_vary_encoding "allow apache"
   :coredump_dir "/var/spool/squid"})

(defn squid-conf-file
  "Write the squid configuration file (squid.conf)

   Options are as for remote-file."
  [request & {:as options}]
  (let [config-dir (parameter/get-for-target request [:squid :config-dir])]
    (->
     request
     (thread-expr/apply-map->
      remote-file/remote-file (str config-dir "squid.conf")
      :flag-on-changed squid-config-changed-flag
      options))))

(def ^{:private true} line-format "%s %s %s")

(declare squid-conf)

(defn- format-squid-conf
  [[key value] prefix]
  (cond
   (map? value) (squid-conf value (conj (or prefix []) (name key)))
   (vector? value) (string/join
                    \newline
                    (map #(format-squid-conf [key %] prefix) value))
   :else (string/trim
          (format line-format (string/join " " prefix) (name key) value))))

(def ^{:doc "Ordering is significant for some keys"}
  ordered-keys
  [:acl :http_access :http_access2 :adapted_http_access :icp_access
   :adaption_access])

(defn squid-conf
  "Create a squid config based on the supplied map.

   You can use `default-options` for a sane default, but should probably merge
   in at least a :cache_dir directive in order to set the cache size."
  ([config] (squid-conf config []))
  ([config prefix]
     (string/join
      \newline
      (map #(format-squid-conf % prefix)
           (concat (map
                    #(vector % (config %))
                    (filter (set (keys config)) ordered-keys))
                   (map
                    #(vector % (config %))
                    (remove (set ordered-keys) (keys config))))))))

(defn configure
  "Configure squid with the specified `config` map. See `default-config` for an
   example of the map entries."
  [request config & {:keys [literal] :or {literal true}}]
  (->
   request
   (squid-conf-file
    :content (squid-conf config)
    :literal literal)
   (parameter/assoc-for-target [:squid :port] (or (:http_port config) 3128))))

(defn iptables-accept
  "Accept proxy requests, by default on port 3128."
  ([request] (iptables-accept
              request (parameter/get-for-target [:squid :port] 3128)))
  ([request port]
     (pallet.crate.iptables/iptables-accept-port request port)))
