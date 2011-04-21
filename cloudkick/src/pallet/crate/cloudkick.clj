(ns pallet.crate.cloudkick
  "Agent install for cloudkick"
  (:require
   [pallet.action.package :as package]
   [pallet.action.remote-file :as remote-file]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]))

(def cloudkick-conf-template "crate/cloudkick/cloudkick.conf")

(defn cloudkick
  "Install cloudkick agent.  Options are:
     :name string     - name to identify node in cloudkick
     :tags seq        - tags for grouping nodes in cloudkick
     :resources       - proxy to port 80 on agent-resources.cloudkick.com
     :endpoint        - proxy to port 4166 on agent-endpoint.cloudkick.com"
  [session nodename oauth-key oauth-secret
   & {:keys [name tags resources endpoint] :as options}]
  (-> session
      (remote-file/remote-file
       "/etc/cloudkick.conf"
       :template cloudkick-conf-template
       :values (merge {:oauth-key oauth-key :oauth-secret oauth-secret
                       :name nodename :tags ["any"] :resources nil
                       :endpoint nil}
                      options))
      (package/package-source
       "cloudkick"
       :aptitude {:url "http://packages.cloudkick.com/ubuntu"
                  :key-url "http://packages.cloudkick.com/cloudkick.packages.key"}
       :yum { :url (str "http://packages.cloudkick.com/redhat/"
                        (stevedore/script (~lib/arch)))})
      (package/package-manager :update)
      (package/package "cloudkick-agent")))
