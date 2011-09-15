(ns pallet.crate.rubygems
 "Installation of rubygems from source"
  (:require
   [pallet.action :as action]
   [pallet.action.conditional :as conditional]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.file :as file]
   [pallet.action.package :as package]
   [pallet.action.remote-file :as remote-file]
   [pallet.action.user :as user]
   [pallet.crate.ruby :as ruby]
   [pallet.script :as script]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils]
   [clojure.contrib.json :as json]))

(script/defscript gem-cmd [action package & [{:as options} & _]])
(script/defimpl gem-cmd :default [action package & options]
  ("gem" ~action ~(stevedore/map-to-arg-string (first options)) ~package))

(def rubygems-downloads
     {"1.4.1" ["http://rubyforge.org/frs/download.php/73779/rubygems-1.4.1.tgz"
               "e915dbf79e22249d10c0fcf503a5adda"]
      "1.4.0" ["http://rubyforge.org/frs/download.php/73763/rubygems-1.4.0.tgz"]
      "1.3.7" ["http://rubyforge.org/frs/download.php/70696/rubygems-1.3.7.tgz"]
      "1.3.6"  ["http://rubyforge.org/frs/download.php/69365/rubygems-1.3.6.tgz"
                "789ca8e9ad1d4d3fe5f0534fcc038a0d"]
      "1.3.5" ["http://rubyforge.org/frs/download.php/60718/rubygems-1.3.5.tgz"
                "6e317335898e73beab15623cdd5f8cff"]})

(defn rubygems
  "Install rubygems from source"
  ([session] (rubygems session "1.3.6"))
  ([session version]
     (let [info (rubygems-downloads version)
           basename (str "rubygems-" version)
           tarfile (str basename ".tgz")
           tarpath (str (stevedore/script (~lib/tmp-dir)) "/" tarfile)]
       (->
        session
        (ruby/ruby-packages)
        (conditional/when
         (< @(~ruby/ruby-version) "1.8.6")
         (ruby/ruby))
        (remote-file/remote-file
         tarpath
         :url (first info)
         :md5 (second info))
        (exec-script/exec-script
         (if-not (pipe ("gem" "--version") (grep (quoted ~version)))
           (do
             ~(stevedore/checked-script
               "Building rubygems"
               ("cd" (~lib/tmp-dir))
               ("tar" xfz ~tarfile)
               ("cd" ~basename)
               ("ruby" setup.rb)
               (if-not (|| (file-exists? "/usr/bin/gem1.8")
                           (file-exists? "/usr/local/bin/gem"))
                 (do (println "Could not find rubygem executable")
                     ("exit" 1)))
               ;; Create a symlink if we only have one ruby version installed
               (if-not (&& (file-exists? "/usr/bin/gem1.8")
                           (file-exists? "/usr/bin/gem1.9"))
                 (if (file-exists? "/usr/bin/gem1.8")
                   ("ln" "-sfv" "/usr/bin/gem1.8" "/usr/bin/gem")))
               (if-not (&& (file-exists? "/usr/local/bin/gem1.8")
                           (file-exists? "/usr/local/bin/gem1.9"))
                 (if (file-exists? "/usr/local/bin/gem1.8")
                   ("ln" "-sfv" "/usr/locl/bin/gem1.8"
                    "/usr/local/bin/gem")))))))))))

(defn rubygems-update
  [session]
  (exec-script/exec-script
   session
   ("gem" "update" "--system")))

(action/def-bash-action gem "Gem management."
  [session name & {:keys [action version no-ri no-rdoc]
                   :or {action :install}
                   :as options}]
  (case action
    :install (stevedore/checked-script
              (format "Install gem %s" name)
              (~gem-cmd
               "install" ~name
               ~(select-keys options [:version :no-ri :no-rdoc])))
    :delete (stevedore/checked-script
             (format "Uninstall gem %s" name)
             (~gem-cmd
              "uninstall" ~name
              ~(select-keys options [:version :no-ri :no-rdoc])))))


(action/def-bash-action gem-source "Gem source management."
  [session source & {:keys [action] :or {action :create} :as options}]
  (case action
    :create (stevedore/script
             (if-not ("gem" "sources" "--list" "|" "grep" ~source)
               (~gem-cmd "sources" ~source ~{:add true})))
    :delete (stevedore/script (~gem-cmd "sources" ~source ~{:remove true}))))

(def remote-file* (action/action-fn remote-file/remote-file-action))

(action/def-bash-action gemrc "rubygems configuration"
  [session m & user?]
  (let [user (or (first user?) (utils/*admin-user* :username))]
    (remote-file*
     session
     (str (stevedore/script (~lib/user-home ~user)) "/.gemrc")
     :content (.replaceAll (json/json-str m) "[{}]" "")
     :owner user)))

(defn require-rubygems
  "Ensure that a version of rubygems is installed"
   [session]
   (exec-script/exec-checked-script
    session
    "Checking for rubygems"
    ("gem" "--version")))
