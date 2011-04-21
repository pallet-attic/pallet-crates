(ns pallet.crate.rubygems-test
  (:use pallet.crate.rubygems)
  (:require
   [pallet.action.exec-script :as exec-script]
   [pallet.action.remote-file :as remote-file]
   [pallet.build-actions :as build-actions]
   [pallet.stevedore :as stevedore])
  (:use
   clojure.test
   pallet.test-utils
))

(use-fixtures :once with-ubuntu-script-template)

(deftest gem-script-test
  (is (= "gem install fred"
         (stevedore/script (~gem-cmd install fred)))))

(deftest gem-test
  (is (= (first
          (build-actions/build-actions
           {}
           (exec-script/exec-checked-script
             "Install gem fred"
             ("gem" install fred))))
         (first (build-actions/build-actions {} (gem "fred"))))))

(deftest gem-source-test
  (is (= (first
          (build-actions/build-actions
           {}
           (exec-script/exec-script
            "if ! gem sources --list | grep http://rubygems.org; then gem sources --add http://rubygems.org;fi\n")))
         (first (build-actions/build-actions
                 {} (gem-source "http://rubygems.org"))))))

(deftest gemrc-test
  (is (= (first
          (build-actions/build-actions
           {}
           (remote-file/remote-file
            "$(getent passwd fred | cut -d: -f6)/.gemrc"
            :content "\"gem\":\"--no-rdoc --no-ri\""
            :owner "fred")))
         (first
          (build-actions/build-actions
           {} (gemrc {:gem "--no-rdoc --no-ri"} "fred"))))))

(deftest invoke-test
  (is (build-actions/build-actions
       {}
       (rubygems)
       (rubygems-update)
       (gem "name")
       (gem "name" :action :delete)
       (gem-source "http://rubygems.org")
       (gemrc {} "user")
       (require-rubygems))))
