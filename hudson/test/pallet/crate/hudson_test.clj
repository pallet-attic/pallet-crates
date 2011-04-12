(ns pallet.crate.hudson-test
  (:use pallet.crate.hudson)
  (:require
   [pallet.crate.automated-admin-user :as automated-admin-user]
   [pallet.template :only [apply-templates]]
   [pallet.core :as core]
   [pallet.live-test :as live-test]
   [pallet.target :as target]
   [pallet.stevedore :as stevedore]
   [pallet.resource :as resource]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.directory :as directory]
   [pallet.resource.exec-script :as exec-script]
   [pallet.resource.network-service :as network-service]
   [pallet.resource.user :as user]
   [pallet.crate.maven :as maven]
   [pallet.crate.tomcat :as tomcat]
   [pallet.utils :as utils]
   [pallet.parameter-test :as parameter-test]
   [net.cgrand.enlive-html :as xml])
  (:use clojure.test
        pallet.test-utils
        [pallet.resource.package :only [package package-manager]]
        [pallet.stevedore :only [script]]))

(def parameters {:host
                 {:id
                  {:tomcat {:owner "tomcat6"
                            :group "tomcat6"
                            :config-path "/etc/tomcat6/"
                            :base "/var/lib/tomcat6/"}}}})
(deftest hudson-tomcat-test
  (is (= (first
          (build-resources
           [:parameters (assoc-in parameters [:host :id :hudson]
                                  {:data-path "/var/lib/hudson"})]
           (directory/directory
            "/var/lib/hudson" :owner "root" :group "tomcat6" :mode "0775")
           (remote-file/remote-file
            "/var/lib/hudson/hudson.war"
            :url (str hudson-download-base-url "latest/jenkins.war")
            :md5 nil)
           (tomcat/policy
            99 "hudson"
            {(str "file:${catalina.base}/webapps/hudson/-")
             ["permission java.security.AllPermission"]
             (str "file:/var/lib/hudson/-")
             ["permission java.security.AllPermission"]})
           (tomcat/application-conf
            "hudson"
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>
 <Context
 privileged=\"true\"
 path=\"/hudson\"
 allowLinking=\"true\"
 swallowOutput=\"true\"
 >
 <Environment
 name=\"HUDSON_HOME\"
 value=\"/var/lib/hudson\"
 type=\"java.lang.String\"
 override=\"false\"/>
 </Context>")
           (tomcat/deploy "hudson" :remote-file "/var/lib/hudson/hudson.war")))
         (first
          (build-resources
           [:parameters parameters]
           (tomcat-deploy)
           (parameter-test/parameters-test
            [:host :id :hudson :data-path] "/var/lib/hudson"
            [:host :id :hudson :user] "tomcat6"
            [:host :id :hudson :group] "tomcat6"))))))

(deftest determine-scm-type-test
  (is (= :git (determine-scm-type ["http://project.org/project.git"]))))

(deftest normalise-scms-test
  (is (= [["http://project.org/project.git"]]
         (normalise-scms ["http://project.org/project.git"]))))

(deftest output-scm-for-git-test
  (is (= "<scm class=\"hudson.plugins.git.GitSCM\">\n  <remoteRepositories>\n    <org.spearce.jgit.transport.RemoteConfig>\n      <string>origin</string>\n      <int>5</int>\n      <string>fetch</string>\n      <string>+refs/heads/*:refs/remotes/origin/*</string>\n      <string>receivepack</string>\n      <string>git-upload-pack</string>\n      <string>uploadpack</string>\n      <string>git-upload-pack</string>\n      <string>url</string>\n      <string>http://project.org/project.git</string>\n      <string>tagopt</string>\n      <string></string>\n    </org.spearce.jgit.transport.RemoteConfig>\n  </remoteRepositories>\n  <branches>\n    <hudson.plugins.git.BranchSpec>\n      <name>*</name>\n    </hudson.plugins.git.BranchSpec>\n  </branches>\n  <mergeOptions></mergeOptions>\n  <doGenerateSubmoduleConfigurations>false</doGenerateSubmoduleConfigurations>\n  <submoduleCfg class=\"list\"></submoduleCfg>\n</scm>"
         (apply str
          (xml/emit*
           (output-scm-for :git {:tag :b :image {:os-family :ubuntu}}
                           "http://project.org/project.git" {}))))))

(deftest output-scm-for-svn-test
  (is (= "<scm class=\"hudson.scm.SubversionSCM\">\n  <locations>\n    <hudson.scm.SubversionSCM_-ModuleLocation>\n      <remote>http://project.org/svn/project</remote>\n    </hudson.scm.SubversionSCM_-ModuleLocation>\n  </locations>\n  <useUpdate>false</useUpdate>\n  <doRevert>false</doRevert>\n  \n  <excludedRegions></excludedRegions>\n  <includedRegions></includedRegions>\n  <excludedUsers></excludedUsers>\n  <excludedRevprop></excludedRevprop>\n  <excludedCommitMessages></excludedCommitMessages>\n</scm>"
         (apply str
          (xml/emit*
           (output-scm-for :svn {:tag :b :image {:os-family :ubuntu}}
                           ["http://project.org/svn/project"] {})))))
  (is (= "<scm class=\"hudson.scm.SubversionSCM\">\n  <locations>\n    <hudson.scm.SubversionSCM_-ModuleLocation>\n      <remote>http://project.org/svn/project/branch/a</remote><remote>http://project.org/svn/project/branch/a</remote>\n    </hudson.scm.SubversionSCM_-ModuleLocation><hudson.scm.SubversionSCM_-ModuleLocation>\n      <remote>http://project.org/svn/project/branch/b</remote><remote>http://project.org/svn/project/branch/b</remote>\n    </hudson.scm.SubversionSCM_-ModuleLocation>\n  </locations>\n  <useUpdate>false</useUpdate>\n  <doRevert>false</doRevert>\n  <browser class=\"c\"><url>url</url></browser>\n  <excludedRegions></excludedRegions>\n  <includedRegions></includedRegions>\n  <excludedUsers></excludedUsers>\n  <excludedRevprop></excludedRevprop>\n  <excludedCommitMessages></excludedCommitMessages>\n</scm>"
         (apply str
          (xml/emit*
           (output-scm-for :svn {:tag :b :image {:os-family :ubuntu}}
                           ["http://project.org/svn/project/branch/"]
                           {:branches ["a" "b"]
                            :browser {:class "c" :url "url"}}))))))

(deftest credential-entry-test
  (is (= [:entry {}
          [:string {} "<http://server.com:80>"]
          [:hudson.scm.SubversionSCM_-DescriptorImpl_-PasswordCredential {}
           [:userName {} "u"]
           [:password {} "cA==\r\n"]]]
         (credential-entry
          ["<http://server.com:80>" {:user-name "u" :password "p"}]))))

(deftest credential-store-test
  (is (= "<?xml version=\"1.0\" encoding=\"UTF-8\"?><hudson.scm.PerJobCredentialStore><credentials class=\"hashtable\"><entry><string>&lt;http://server.com:80&gt;</string><hudson.scm.SubversionSCM_-DescriptorImpl_-PasswordCredential><userName>u</userName><password>cA==\r\n</password></hudson.scm.SubversionSCM_-DescriptorImpl_-PasswordCredential></entry></credentials></hudson.scm.PerJobCredentialStore>"
         (credential-store
          {"<http://server.com:80>" {:user-name "u" :password "p"}}))))

(deftest plugin-property-test
  (is (= {:tag "hudson.plugins.jira.JiraProjectProperty"
          :content [{:content "http://jira.somewhere.com/", :tag "siteName"}]}
         (plugin-property [:jira {:siteName "http://jira.somewhere.com/"}])))
  (is (= {:tag "hudson.security.AuthorizationMatrixProperty"
          :content [{:tag "permission" :content "hudson.model.Item.Read:me"}
                    {:tag "permission" :content "hudson.model.Item.Build:me"}]}
         (plugin-property [:authorization-matrix
                           [{:user "me"
                             :permissions #{:item-build :item-read}}]]))))

(deftest hudson-job-test
  (core/defnode n {})
  (is (= (first
          (build-resources
           []
           (directory/directory
            "/var/lib/hudson/jobs/project" :p true
            :owner "root" :group "tomcat6" :mode "0775")
           (remote-file/remote-file
            "/var/lib/hudson/jobs/project/config.xml"
            :content "<?xml version='1.0' encoding='utf-8'?>\n<maven2-moduleset>\n  <actions></actions>\n  <description></description>\n  <logRotator>\n    <daysToKeep>-1</daysToKeep>\n    <numToKeep>-1</numToKeep>\n    <artifactDaysToKeep>-1</artifactDaysToKeep>\n    <artifactNumToKeep>-1</artifactNumToKeep>\n  </logRotator>\n  <keepDependencies>false</keepDependencies>\n  <properties><hudson.plugins.disk__usage.DiskUsageProperty></hudson.plugins.disk__usage.DiskUsageProperty></properties>\n  <scm class=\"hudson.plugins.git.GitSCM\">\n  <remoteRepositories>\n    <org.spearce.jgit.transport.RemoteConfig>\n      <string>origin</string>\n      <int>5</int>\n      <string>fetch</string>\n      <string>+refs/heads/*:refs/remotes/origin/*</string>\n      <string>receivepack</string>\n      <string>git-upload-pack</string>\n      <string>uploadpack</string>\n      <string>git-upload-pack</string>\n      <string>url</string>\n      <string>http://project.org/project.git</string>\n      <string>tagopt</string>\n      <string></string>\n    </org.spearce.jgit.transport.RemoteConfig>\n  </remoteRepositories>\n  <branches>\n    <hudson.plugins.git.BranchSpec>\n      <name>origin/master</name>\n    </hudson.plugins.git.BranchSpec>\n  </branches>\n  <mergeOptions></mergeOptions>\n  <doGenerateSubmoduleConfigurations>false</doGenerateSubmoduleConfigurations>\n  <submoduleCfg class=\"list\"></submoduleCfg>\n</scm>\n  <canRoam>true</canRoam>\n  <disabled>false</disabled>\n  <blockBuildWhenUpstreamBuilding>false</blockBuildWhenUpstreamBuilding>\n  \n  <triggers class=\"vector\">\n    <hudson.triggers.SCMTrigger>\n      <spec>*/15 * * * *</spec>\n    </hudson.triggers.SCMTrigger>\n  </triggers>\n  <concurrentBuild>false</concurrentBuild>\n  <rootModule>\n    <groupId>project</groupId>\n    <artifactId>artifact</artifactId>\n  </rootModule>\n  <goals>clojure:test</goals>\n  <defaultGoals></defaultGoals>\n  \n  <mavenOpts>-Dx=y</mavenOpts>\n  <mavenName>base maven</mavenName>\n  <aggregatorStyleBuild>true</aggregatorStyleBuild>\n  <incrementalBuild>false</incrementalBuild>\n  <usePrivateRepository>false</usePrivateRepository>\n  <ignoreUpstremChanges>false</ignoreUpstremChanges>\n  <archivingDisabled>false</archivingDisabled>\n  <reporters></reporters>\n  <publishers></publishers>\n  <buildWrappers></buildWrappers>\n</maven2-moduleset>"
            :owner "root" :group "tomcat6" :mode "0664")
           (directory/directory
            "/var/lib/hudson"
            :owner "root" :group "tomcat6"
            :mode "g+w"
            :recursive true)))
         (first
          (build-resources
           [:node-type {:image {:os-family :ubuntu}}
            :parameters {:host {:id {:hudson {:data-path "/var/lib/hudson"
                                              :user "tomcat6"
                                              :group "tomcat6"
                                              :owner "root"}}}}]
           (job
            :maven2 "project"
            :maven-opts "-Dx=y"
            :branches ["origin/master"]
            :scm ["http://project.org/project.git"]
            :properties {:disk-usage {}}))))))


(deftest hudson-maven-xml-test
  (core/defnode test-node {:os-family :ubuntu})
  (is (= "<?xml version='1.0' encoding='utf-8'?>\n<hudson.tasks.Maven_-DescriptorImpl>\n  <installations>\n    <hudson.tasks.Maven_-MavenInstallation>\n      <name>name</name>\n      <home>/var/lib/hudson/tools/name</home>\n      \n    </hudson.tasks.Maven_-MavenInstallation>\n  </installations>\n</hudson.tasks.Maven_-DescriptorImpl>"
         (apply str (hudson-maven-xml
                      test-node
                      "/var/lib/hudson"
                      [["name" "2.2.0"]])))))

(deftest hudson-maven-test
  (is (= (first
          (build-resources
           []
           (maven/download
            :maven-home "/var/lib/hudson/tools/default_maven"
            :version "2.2.0"
            :owner "root"
            :group "tomcat6")
           (directory/directory
            "/usr/share/tomcat6/.m2" :group "tomcat6" :mode "g+w")
           (directory/directory
            "/var/lib/hudson" :owner "root" :group "tomcat6" :mode "775")
           (remote-file/remote-file
            "/var/lib/hudson/hudson.tasks.Maven.xml"
            :content "<?xml version='1.0' encoding='utf-8'?>\n<hudson.tasks.Maven_-DescriptorImpl>\n  <installations>\n    <hudson.tasks.Maven_-MavenInstallation>\n      <name>default maven</name>\n      <home>/var/lib/hudson/tools/default_maven</home>\n      \n    </hudson.tasks.Maven_-MavenInstallation>\n  </installations>\n</hudson.tasks.Maven_-DescriptorImpl>"
            :owner "root"
            :group "tomcat6")))
         (first
          (build-resources
           [:node-type {:image {:os-family :ubuntu}}
            :parameters {:host
                         {:id {:hudson {:user "tomcat6" :group "tomcat6"
                                        :owner "root"
                                        :data-path "/var/lib/hudson"}}}}]
           (maven "default maven" "2.2.0"))))))

(deftest plugin-test
  (is (= (first
          (build-resources
           []
           (directory/directory "/var/lib/hudson/plugins")
           (user/user "tomcat6" :action :manage :comment "hudson")
           (remote-file/remote-file
            "/var/lib/hudson/plugins/git.hpi"
            :group "tomcat6" :mode "0664"
            :url (default-plugin-path :git :latest))))
         (first
          (build-resources
           [:parameters (assoc-in parameters
                                  [:host :id :hudson]
                                  {:data-path "/var/lib/hudson"
                                   :group "tomcat6"
                                   :user "tomcat6"})]
           (plugin :git)))))
  (is (= (first
          (build-resources
           []
           (directory/directory "/var/lib/hudson/plugins")
           (user/user "tomcat6" :action :manage :comment "hudson")
           (remote-file/remote-file
            "/var/lib/hudson/plugins/git.hpi"
            :group "tomcat6" :mode "0664"
            :url (default-plugin-path :git "1.15"))))
         (first
          (build-resources
           [:parameters (assoc-in parameters
                                  [:host :id :hudson]
                                  {:data-path "/var/lib/hudson"
                                   :group "tomcat6"
                                   :user "tomcat6"})]
           (plugin :git :version "1.15"))))))

(deftest invocation
  (is (build-resources
       [:parameters parameters]
       (tomcat-deploy)
       (parameter-test/parameters-test
        [:host :id :hudson :user] "tomcat6"
        [:host :id :hudson :group] "tomcat6")
       (maven "name" "2.2.1")
       (job :maven2 "job")
       (plugin :git)))
  (is (build-resources
       [:parameters parameters]
       (tomcat-deploy)
       (tomcat-undeploy))))

(deftest publisher-test
  (testing "artifact archiver"
    (is (= (str "<hudson.tasks.ArtifactArchiver>"
                "<artifacts>**/*.war</artifacts><latestOnly>false</latestOnly>"
                "</hudson.tasks.ArtifactArchiver>")
           (publisher-config [:artifact-archiver {:artifacts "**/*.war"}]))))
  (testing "build trigger"
    (testing "default threshold"
      (is (= (str "<hudson.tasks.BuildTrigger>"
                  "<childProjects>a,b</childProjects>"
                  "<threshold><name>SUCCESS</name>"
                  "<ordinal>0</ordinal><color>BLUE</color></threshold>"
                  "</hudson.tasks.BuildTrigger>")
             (publisher-config [:build-trigger {:child-projects "a,b"}]))))
    (testing "with unstable threshold"
      (is (= (str "<hudson.tasks.BuildTrigger>"
                  "<childProjects>a,b</childProjects>"
                  "<threshold><name>UNSTABLE</name>"
                  "<ordinal>1</ordinal><color>YELLOW</color></threshold>"
                  "</hudson.tasks.BuildTrigger>")
             (publisher-config
              [:build-trigger
               {:child-projects "a,b" :threshold :unstable}]))))))

(def unsupported [{:os-family :debian}]) ; no tomcat6

(deftest live-test
  (doseq [image (live-test/exclude-images live-test/*images* unsupported)]
    (live-test/test-nodes
     [compute node-map node-types]
     {:hudson
      {:image (update-in image [:min-ram] #(max (or % 0) 512))
       :count 1
       :phases {:bootstrap (resource/phase
                            (automated-admin-user/automated-admin-user))
                :configure (resource/phase
                            (tomcat/install :version 6)
                            (tomcat-deploy)
                            (config)
                            (plugin :git :version "1.1.5")
                            (plugin :jira)
                            (plugin :disk-usage)
                            (plugin :shelve-project-plugin)
                            (job
                             :maven2 "gitjob"
                             :maven-name "default maven"
                             :scm ["git://github.com/hugoduncan/pallet.git"])
                            (job
                             :maven2 "svnjob"
                             :maven-name "default maven"
                             :scm ["http://svn.host.com/project"]
                             :subversion-credentials
                             {"somename"
                              {:user-name "u" :password "p"}})
                            (tomcat/init-service :action :restart))
                :verify (resource/phase
                         ;; hudson takes a while to start up
                         (network-service/wait-for-http-status
                          "http://localhost:8080/hudson" 200
                          :max-retries 10 :url-name "hudson")
                         (exec-script/exec-checked-script
                          "check hudson installed"
                          (wget "-O-" "http://localhost:8080/hudson")
                          (wget "-O-" "http://localhost:8080/hudson/job/gitjob")
                          (wget
                           "-O-" "http://localhost:8080/hudson/job/svnjob")
                          ("test"
                           (file-exists?
                            "/var/lib/hudson/jobs/svnjob/subversion.credentials"))))}}}
     (core/lift (:hudson node-types) :phase :verify :compute compute))))
