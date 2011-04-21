(ns pallet.crate.github-test
  (:use pallet.crate.github)
  (:require
   [pallet.build-actions :as build-actions]
   [clojure.contrib.condition :as condition])
  (:use
   clojure.test
   pallet.test-utils))

(deftest invoke-test
  (let [akey "key12344"]
    (binding [pallet.crate.github/api (fn [& _] {:public_keys [{:key akey}]})]
      (is
       (build-actions/build-actions
        {}
        (deploy-key "project" "title" akey :username "u" :apikey "a")
        (deploy-key "project" "title" akey :username "u" :password "a")))
      (is
       (build-actions/build-actions
        {:parameters {:github {:username "u" :password "p"}}}
        (deploy-key "project" "title" akey)))
      (is
       (build-actions/build-actions
        {:parameters {:github {:username "u" :apikey "p"}}}
        (deploy-key "project" "title" akey)))
      (is (thrown?
           clojure.contrib.condition.Condition
           (build-actions/build-actions
            {}
            (deploy-key "project" "title" akey)))))))
