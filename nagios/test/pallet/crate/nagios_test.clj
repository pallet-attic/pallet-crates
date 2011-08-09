(ns pallet.crate.nagios-test
  (:use pallet.crate.nagios)
  (:require
   [pallet.action :as action]
   [pallet.action.file :as file]
   [pallet.action.remote-file :as remote-file]
   [pallet.build-actions :as build-actions]
   [pallet.parameter :as parameter]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore]
   [pallet.test-utils :as test-utils]
   [clojure.string :as string])
  (:use clojure.test))

(use-fixtures
 :once
 test-utils/with-ubuntu-script-template
 test-utils/with-bash-script-language)

(def remote-file* (action/action-fn remote-file/remote-file-action))

;; This is taken from bagios-config to avoid circular dependencies
(defn service
  [session {:keys [host_name] :as options}]
  (parameter/update-for session
   [:nagios :host-services
    (keyword (or host_name (nagios-hostname
                            (session/target-node session))))]
   (fn [x]
     (distinct
      (conj
       (or x [])
       options)))))

(deftest host-service-test
  (testing "config"
    (let [safe-name (format "tag%s" (session/safe-id "id"))]
      (is (= (str
              (remote-file*
               {}
               "/etc/nagios3/conf.d/pallet-host-*.cfg"
               :action :delete :force true)
              (remote-file*
               {}
               (format "/etc/nagios3/conf.d/pallet-host-%s.cfg" safe-name)
               :content
               (str
                "\ndefine host "
                "{\n use generic-host\n host_name " safe-name
                "\n alias " safe-name
                "\n address 1.2.3.4\n}\n"
                (define-service {:check_command "check_cmd"
                                 :service_description "Service Name"
                                 :host_name safe-name
                                 :notification_interval 0
                                 :use "generic-service"}))
               :owner "root"))
             (let [node (test-utils/make-node
                         "tag" :id "id" :public-ips ["1.2.3.4"])]
               (first
                (build-actions/build-actions
                 {:all-nodes [node]
                  :server {:image {:os-family :ubuntu} :node node}}
                 (service
                  {:check_command "check_cmd"
                   :service_description "Service Name"})
                 (hosts))))))))
  (testing "unmanaged host config"
    (is (= (str
            (remote-file*
             {}
             "/etc/nagios3/conf.d/pallet-host-*.cfg"
             :action :delete :force true)
            (remote-file*
             {}
             "/etc/nagios3/conf.d/pallet-host-tag.cfg"
             :content
             (str
              "\ndefine host {\n use generic-host\n host_name tag\n alias tag\n address 1.2.3.4\n}\n"
              (define-service {:check_command "check_cmd"
                               :service_description "Service Name"
                               :host_name "tag"
                               :notification_interval 0
                               :use "generic-service"}))
             :owner "root"))
           (str
            (let [node (test-utils/make-node
                        "tag" :id "id" :public-ips ["1.2.3.4"])]
              (first
               (build-actions/build-actions
                {:all-nodes [node]
                 :server {:image {:os-family :ubuntu} :node node}}
                (unmanaged-host "1.2.3.4" "tag")
                (service
                 {:host_name "tag"
                  :check_command "check_cmd"
                  :service_description "Service Name"})
                (hosts)))))))))

(deftest define-contact-test
  (is (= "define contact{\n email email\n contact_name name\n}\n"
         (define-contact {:contact_name "name" :email "email"}))))

(deftest contact-test
  (is (= (stevedore/do-script
          (remote-file*
           {}
           "/etc/nagios3/conf.d/pallet-contacts.cfg"
           :action :delete :force true)
          (remote-file*
           {}
           "/etc/nagios3/conf.d/pallet-contacts.cfg"
           :owner "root"
           :content (str
                     (define-contact {:contact_name "name"
                                      :email "email"
                                      :contactgroups ["admin" "ops"]})
                     \newline
                     (define-contact {:contact_name "name2"
                                      :email "email2"
                                      :contactgroups ["admin"]})
                     \newline
                     (define-contactgroup {:contactgroup_name "admin"})
                     \newline
                     (define-contactgroup {:contactgroup_name "ops"}))))
         (first
          (build-actions/build-actions
           {}
           (contact {:contact_name "name"
                     :email "email"
                     :contactgroups ["admin" "ops"]})
           (contact {:contact_name "name2" :email "email2"
                     :contactgroups [:admin]}))))))
