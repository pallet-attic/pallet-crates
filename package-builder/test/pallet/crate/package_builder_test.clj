(ns pallet.crate.package-builder-test
  (:use pallet.crate.package-builder)
  (:require
   [pallet.resource :as resource]
   [pallet.resource.package :as package])
  (:use clojure.test
        pallet.test-utils))

(deftest simple-test
  []
  (let [a {:tag :n :image {:packager :yum}}]
    (is
     (build-resources
      [:node-type a]
      (yum-package-setup)
      (yum-mock-config "default"
       {:root "epel-5-i386"
        :target_arch "i386"
        :chroot_setup_cmd "install buildsys-build"
        :dist "el5"
        :yum.conf
        (str
         "[main]\ngpgcheck=0\n"
         (package/format-source
          :yum "localmedia" {:url "file:///media/cdrom"}))})))))
