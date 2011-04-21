(ns pallet.crate.mysql-test
  (:use pallet.crate.mysql)
  (:use clojure.test
        pallet.test-utils)
  (:require
   [pallet.action.package :as package]
   [pallet.build-actions :as build-actions]
   [pallet.parameter :as parameter]
   [pallet.parameter-test :as parameter-test]
   [pallet.stevedore :as stevedore]))

(use-fixtures :once with-ubuntu-script-template)

(deftest mysql-server-test
  (is (=
       (first
        (build-actions/build-actions
         {}
         (package/package-manager
          :debconf
          (str "mysql-server-5.1 mysql-server/root_password password " "pwd")
          (str
           "mysql-server-5.1 mysql-server/root_password_again password " "pwd")
          (str "mysql-server-5.1 mysql-server/start_on_boot boolean " true)
          (str
           "mysql-server-5.1 mysql-server-5.1/root_password password " "pwd")
          (str
           "mysql-server-5.1 mysql-server-5.1/root_password_again password "
           "pwd")
          (str "mysql-server-5.1 mysql-server/start_on_boot boolean " true))
         (package/package  "mysql-server")))
       (first
        (build-actions/build-actions
         {:server {:group-name :n :image {:os-family :ubuntu}}}
         (mysql-server "pwd"))))))

(deftest mysql-conf-test
  (is (= "file=/etc/mysql/my.cnf\ncat > ${file} <<EOF\n[client]\nport = 3306\n\nEOF\nchmod 0440 ${file}\nchown root ${file}\n"
         (first (build-actions/build-actions
                 {} (mysql-conf "[client]\nport = 3306\n"))))))

(deftest create-database-test
  (is (= (first
          (build-actions/build-actions
           {} (mysql-script "root" "pwd" "CREATE DATABASE IF NOT EXISTS `db`")))
         (first (build-actions/build-actions
                 {} (create-database "db" "root" "pwd"))))))

(deftest create-user-test
  (is (= (first
          (build-actions/build-actions
           {}
           (mysql-script
            "root" "pwd" "GRANT USAGE ON *.* TO user IDENTIFIED BY 'pw'")))
         (first (build-actions/build-actions
                 {} (create-user "user" "pw" "root" "pwd"))))))

(deftest grant-test
  (is (= (first
          (build-actions/build-actions
           {}
           (mysql-script
            "root" "pwd" "GRANT ALL PRIVILEGES ON `db`.* TO user")))
         (first
          (build-actions/build-actions
           {} (grant "ALL PRIVILEGES" "`db`.*" "user" "root" "pwd"))))))

(deftest invoke-test
  (is (build-actions/build-actions
       {}
       (mysql-client)
       (mysql-server "pwd")
       (parameter-test/parameters-test
        [:mysql :root-password] "pwd")
       (mysql-conf {})
       (mysql-script "root" "pwd" "s")
       (create-database "db")
       (create-user "user" "pwd")
       (grant "p" "level" "user"))))
