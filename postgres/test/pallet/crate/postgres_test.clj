(ns pallet.crate.postgres-test
  (:require
   [pallet.action.exec-script :as exec-script]
   [pallet.action.package :as package]
   [pallet.build-actions :as build-actions]
   [pallet.core :as core]
   [pallet.crate.automated-admin-user :as automated-admin-user]
   [pallet.crate.postgres :as postgres]
   [pallet.live-test :as live-test]
   [pallet.phase :as phase]
   [pallet.test-utils :as test-utils]
   [clojure.contrib.logging :as logging])
  (:use clojure.test))

(deftest postgres-test
  (is ; just check for compile errors for now
   (build-actions/build-actions
    {}
    (postgres/postgres "8.0")
    (postgres/postgres "9.0")
    (postgres/hba-conf :records [])
    (postgres/postgresql-script :content "some script")
    (postgres/create-database "db")
    (postgres/create-role "user"))))

(def pgsql-9-unsupported
  [{:os-family :debian :os-version-matches "5.0.7"}
   {:os-family :debian :os-version-matches "5.0"}])

(deftest live-test
  (live-test/test-for
   [image (live-test/exclude-images (live-test/images) pgsql-9-unsupported)]
   (logging/trace (format "postgres live test: image %s" (pr-str image)))
   (live-test/test-nodes
    [compute node-map node-types]
    {:pgtest
     (->
      (core/server-spec
       :phases {:bootstrap (phase/phase-fn
                            (package/minimal-packages)
                            (package/package-manager :update)
                            (automated-admin-user/automated-admin-user))
                :settings (phase/phase-fn
                           (postgres/settings
                            (postgres/settings-map {})))
                :configure (phase/phase-fn
                            (postgres/postgres))
                :verify (phase/phase-fn
                         (postgres/initdb)
                         (postgres/hba-conf)
                         (postgres/postgresql-conf)
                         (postgres/create-database "db")
                         (postgres/create-role "user")
                         (exec-script/exec-checked-script
                          "check postgres functional"
                          (pipe (psql --version) (grep "9.0"))))}
       :count 1
       :node-spec (core/node-spec :image image)))}
    (is
     (core/lift
      (val (first node-types)) :phase [:verify] :compute compute)))))
