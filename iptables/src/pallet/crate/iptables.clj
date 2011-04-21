(ns pallet.crate.iptables
  "Crate for managing iptables"
  (:require
   [pallet.action :as action]
   [pallet.action.file :as file]
   [pallet.action.remote-file :as remote-file]
   [pallet.argument :as argument]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore]
   [clojure.contrib.string :as string]
   [clojure.contrib.logging :as logging]))

(def prefix
     {"filter" ":INPUT ACCEPT
:FORWARD ACCEPT
:OUTPUT ACCEPT
:FWR -
-A INPUT -j FWR
-A FWR -i lo -j ACCEPT"})
(def suffix
     {"filter" "# Rejects all remaining connections with port-unreachable errors.
-A FWR -p tcp -m tcp --tcp-flags SYN,RST,ACK SYN -j REJECT --reject-with icmp-port-unreachable
-A FWR -p udp -j REJECT --reject-with icmp-port-unreachable
COMMIT
"})

(def ^{:private true}
  remote-file* (action/action-fn remote-file/remote-file-action))

(defn restore-iptables
  [session [table rules]]
  (pallet.stevedore/script
   (var tmp @(mktemp iptablesXXXX))
   ~(remote-file* session "$tmp" :content rules)
   ~(pallet.stevedore/checked-script
     "Restore IPtables"
     ("/sbin/iptables-restore" < @tmp))
   (rm @tmp)))

(defn format-iptables
  [tables]
  (string/join \newline (map second tables)))


(action/def-aggregated-action iptables-rule
  "Define a rule for the iptables. The argument should be a string containing an
iptables configuration line (cf. arguments to an iptables invocation)"
  {:arglists '([session table config-line])}
  [session args]
  (let [args (group-by first args)
        tables (into
                {}
                (map
                 #(vector
                   (first %)
                   (str
                    "*" (first %) \newline
                    (string/join
                     \newline (filter
                               identity
                               [(prefix (first %))
                                (string/join
                                 \newline
                                 (map second (second %)))
                                (suffix (first %) "COMMIT\n")])))) args))
        packager (session/packager session)]
    (case packager
      :aptitude (stevedore/do-script*
                 (map #(restore-iptables session %) tables))
      :yum (stevedore/do-script
            (remote-file*
             session
             "/etc/sysconfig/iptables"
             :mode "0755"
             :content (format-iptables tables))
            (stevedore/script
             ("/sbin/iptables-restore" < "/etc/sysconfig/iptables"))))))

(defn iptables-accept-established
  "Accept established connections"
  [session]
  (iptables-rule
   session "filter" "-A FWR -m state --state RELATED,ESTABLISHED -j ACCEPT"))

(defn iptables-accept-icmp
  "Accept ICMP"
  [session]
  (iptables-rule session "filter" "-A FWR -p icmp -j ACCEPT"))

(defonce accept-option-strings
  {:source " -s %s" :source-range " -src-range %s"})

(defn iptables-accept-port
  "Accept specific port, by default for tcp."
  ([session port] (iptables-accept-port session port "tcp"))
  ([session port protocol & {:keys [source source-range] :as options}]
     (iptables-rule
      session "filter"
      (format
       "-A FWR -p %s%s --dport %s -j ACCEPT"
       protocol
       (reduce
        #(str %1 (format
                  ((first %2) accept-option-strings)
                  (second %2)))
        "" options)
       port))))

(defn iptables-redirect-port
  "Redirect a specific port, by default for tcp."
  ([session from-port to-port]
     (iptables-redirect-port session from-port to-port "tcp"))
  ([session from-port to-port protocol]
     (iptables-rule
      session "nat"
      (format "-I PREROUTING -p %s --dport %s -j REDIRECT --to-port %s"
              protocol from-port to-port))))

(defn iptables-throttle
  "Throttle repeated connection attempts.
   http://hostingfu.com/article/ssh-dictionary-attack-prevention-with-iptables"
  ([session name port] (iptables-throttle session name port "tcp" 60 4))
  ([session name port protocol time-period hitcount]
     (iptables-rule
      session "filter"
      (format
       "-N %s
-A FWR -p %s --dport %s -m state --state NEW -j %s
-A %s -m recent --set --name %s
-A %s -m recent --update --seconds %s --hitcount %s --name %s -j DROP"
       name protocol port name name name name time-period hitcount name))))
