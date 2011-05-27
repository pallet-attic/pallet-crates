(ns pallet.crate.ganglia-test
  (:use pallet.crate.ganglia)
  (:use clojure.test)
  (:require
   [pallet.build-actions :as build-actions]
   [pallet.test-utils :as test-utils]))

(deftest format-value-test
  (testing "basic map"
    (is (= "a {\nb = \"c\"\nd = 1\n}\n"
           (format-value {:a {:b "c" :d 1}}))))
  (testing "nested map"
    (is (= "a {\nb {\nc = \"d\"\n}\n}\n"
           (format-value {:a {:b {:c "d"}}}))))
  (testing "array value"
    (is (= "a {\nb {\nc = 1\n}\nb {\nd = 2\n}\n}\n"
           (format-value {:a {:b [{:c 1} {:d 2}]}}))))
  (testing "include"
    (is (= "include (\"/a/b/c\")\n"
           (format-value {:include "/a/b/c"})))))

(testing "invoke"
  (is (build-actions/build-actions
       (test-utils/target-server
        :image {:os-family :ubuntu}
        :group-name :gn
        :node (test-utils/make-node "gn" :id "id"))
       (install)
       (monitor)
       (configure)
       (metrics {})
       (check-ganglia-script)
       (nagios-monitor-metric "m" 90 100))))
