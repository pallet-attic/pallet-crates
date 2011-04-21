(ns pallet.crate.nagios-config-test
  (:use pallet.crate.nagios-config)
  (:require
   [pallet.build-actions :as build-actions]
   [pallet.session :as session]
   [pallet.crate.nagios :as nagios]
   [pallet.test-utils :as test-utils])
  (:use clojure.test))

(deftest service*-test
  (let [cfg {:service-group "g" :service-description "d" :command "c"}
        node (test-utils/make-node "tag" :id "id")
        session {:server {:node node}}
        host-id (keyword (format "tag%s" (session/safe-id "id")))]
    (is (= [cfg]
             (-> (service session cfg)
                 :parameters :nagios :host-services host-id)))))

(deftest command-test
  (let [cfg {:command_name "n" :command_line "c"}
        node (test-utils/make-node "tag")
        session {:server {:node node}}]

    (is (= {:n "c"}
           (-> (apply command session (apply concat cfg))
               :parameters :nagios :commands)))))

(deftest invoke-test
  (is (build-actions/build-actions
       {:server {:node (test-utils/make-node "tag" :id "id")}}
       ;; without server
       (nrpe-client)
       (nrpe-client-port)
       ;; with server
       (nagios/nagios "pwd")
       (nrpe-client)
       (nrpe-client-port))))
