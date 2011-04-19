(ns pallet.crate.tomcat-test
  (:refer-clojure :exclude [alias])
  (:require
    [pallet.action :as action]
    [pallet.action.directory :as directory]
    [pallet.action.exec-script :as exec-script]
    [pallet.action.file :as file]
    [pallet.action.package :as package]
    [pallet.action.remote-file :as remote-file]
    [pallet.blobstore :as blobstore]
    [pallet.build-actions :as build-actions]
    [pallet.action :as action]
    [pallet.compute :as compute]
    [pallet.core :as core]
    [pallet.crate.automated-admin-user :as automated-admin-user]
    [pallet.crate.java :as java]
    [pallet.crate.tomcat :as tc]
    [pallet.crate.tomcat :as tomcat]
    [pallet.enlive :as enlive]
    [pallet.live-test :as live-test]
    [pallet.parameter :as parameter]
    [pallet.parameter-test :as parameter-test]
    [pallet.phase :as phase]
    [pallet.thread-expr :as thread-expr])
  (:use
   clojure.test
   pallet.test-utils))

(use-fixtures :once with-ubuntu-script-template)

(deftest install-test
  (is (= (first
          (build-actions/build-actions
           {}
           (package/package-manager :update)
           (package/package "tomcat6")
           (directory/directory
            (stevedore/script (user-home "tomcat6"))
            :owner "tomcat6" :group "tomcat6" :mode "0755")
           (exec-script/exec-checked-script
            "Check tomcat is at /var/lib/tomcat6/"
            (if-not (directory? "/var/lib/tomcat6/")
              (do
                (println "Tomcat not installed at expected location")
                (exit 1))))))
         (first
          (build-actions/build-actions
           {}
           (tomcat/install)
           (parameter-test/parameters-test
            [:host :id :tomcat :base] "/var/lib/tomcat6/"))))))

(deftest classname-for-test
  (let [m {:a "a" :b "b"}]
    (is (= "a" (tomcat/classname-for :a m)))
    (is (= "b" (tomcat/classname-for :b m)))
    (is (= "c" (tomcat/classname-for "c" m)))))

(deftest tomcat-deploy-test
  (is (= (first
          (build-actions/build-actions
           {}
           (remote-file/remote-file "/p/webapps/ROOT.war"
            :remote-file "file.war" :owner "o" :group "g" :mode "600")))
         (first
          (build-actions/build-actions
           {:parameters
            {:host {:id {:tomcat {:base "/p/" :owner "o" :group "g"}}}}}
          (tomcat/deploy nil :remote-file "file.war"))))))

(deftest tomcat-undeploy-all-test
  (is (= "rm -r -f /p/webapps/*\n"
         (first
          (build-actions/build-actions
           {:parameters {:host {:id {:tomcat {:base "/p/"}}}}}
           (tomcat/undeploy-all))))))

(deftest tomcat-undeploy-test
  (is (= (first
          (build-actions/build-actions
           {}
           (directory/directory "/p/webapps/ROOT" :action :delete)
           (file/file "/p/webapps/ROOT.war" :action :delete)
           (directory/directory "/p/webapps/app" :action :delete)
           (file/file "/p/webapps/app.war" :action :delete)
           (directory/directory "/p/webapps/foo" :action :delete)
           (file/file "/p/webapps/foo.war" :action :delete)))
         (first
          (build-actions/build-actions
           {:parameters {:host {:id {:tomcat {:base "/p/"}}}}}
           (tomcat/undeploy nil :app "foo")))))
  (is (= ""
         (first (build-actions/build-actions
                 {:parameters
                  {:host {:id {:tomcat {:base "/p/"}}}}}
                 (tomcat/undeploy))))))

(deftest tomcat-policy-test
  (is (= (first
          (build-actions/build-actions
           {}
           (directory/directory "/p/policy.d")
           (remote-file/remote-file
            "/p/policy.d/100hudson.policy"
            :content "grant codeBase \"file:${catalina.base}/webapps/hudson/-\" {\n  permission java.lang.RuntimePermission \"getAttribute\";\n};"
            :flag-on-changed tomcat/tomcat-config-changed-flag
            :literal true)))
         (first
          (build-actions/build-actions
           {:parameters {:host {:id {:tomcat {:config-path "/p/"}}}}}
           (tomcat/policy
            100 "hudson"
            {"file:${catalina.base}/webapps/hudson/-"
             ["permission java.lang.RuntimePermission \"getAttribute\""]}))))))

(deftest tomcat-blanket-policy-test
  (is (= (first
          (build-actions/build-actions
           {}
           (directory/directory "/p/policy.d")
           (remote-file/remote-file
            "/p/policy.d/100hudson.policy"
            :content "grant  {\n  permission java.lang.RuntimePermission \"getAttribute\";\n};"
            :flag-on-changed tomcat/tomcat-config-changed-flag
            :literal true)))
         (first
          (build-actions/build-actions
           {:parameters {:host {:id {:tomcat {:config-path "/p/"}}}}}
           (tomcat/policy
            100 "hudson"
            {nil ["permission java.lang.RuntimePermission \"getAttribute\""]}))))))

(deftest tomcat-application-conf-test
  (is (= (first
          (build-actions/build-actions
           {}
           (directory/directory "/p/Catalina/localhost/")
           (remote-file/remote-file
            "/p/Catalina/localhost/hudson.xml"
            :content "content"
            :flag-on-changed tomcat/tomcat-config-changed-flag
            :literal true)))
         (first
          (build-actions/build-actions
           {:parameters {:host {:id {:tomcat {:config-path "/p/"}}}}}
           (tomcat/application-conf
            "hudson"
            "content"))))))

(deftest tomcat-user-test
  (is (= "<decl version=\"1.1\"/><tomcat-users><role rolename=\"r1\"/><role rolename=\"r2\"/><user username=\"u2\" password=\"p2\" roles=\"r1,r2\"/><user username=\"u1\" password=\"p1\" roles=\"r1\"/></tomcat-users>\n"
         (first
          (build-actions/build-actions
           {}
           (tomcat/user
            :role "r1" "u1" {:password "p1" :roles ["r1"]})
           (tomcat/user
            :role "r2" "u2" {:password "p2" :roles ["r1","r2"]}))))))

(deftest extract-member-keys-test
  (are [#{:m} #{:c} [:m 1 :c 2 :c 3 :d 1]] =
       (tomcat/extract-member-keys
        [:members [:m] :collections [:c] :m 1 :c 2 :c 3 :d 1]))
  (are [#{:m} #{} [:m 1 :c 2 :c 3 :d 1]] =
       (tomcat/extract-member-keys [:members [:m] :m 1 :c 2 :c 3 :d 1]))
  (are [#{} #{} [:m 1 :c 2 :c 3 :d 1]] =
       (tomcat/extract-member-keys [:m 1 :c 2 :c 3 :d 1])))

(deftest extract-nested-maps-test
  (is (= {:d 1
          :c [{::tc/pallet-type :c :v 2} {::tc/pallet-type :c :v 3}]
          :m {::tc/pallet-type :m :v 1}}
         (tomcat/extract-nested-maps
          [#{:m} #{:c}
           [{::tc/pallet-type :m :v 1}
            {::tc/pallet-type :c :v 2}
            {::tc/pallet-type :c :v 3}
            :d 1]]))))

(deftest pallet-type-test
  (= {::tc/pallet-type :t :u {::tc/pallet-type :u :v 1} :a 1}
     (tomcat/pallet-type :t :member [:u] [:a 1 {::tc/pallet-type :u :v 1}]))
  (= {::tc/pallet-type :t :a 1}
     (tomcat/pallet-type :t [:a 1])))

(deftest alias-test
  (is (= {::tc/pallet-type ::tc/alias :name "fred"}
         (tomcat/alias "fred"))))

(deftest connector-test
  (is (= {::tc/pallet-type ::tc/connector :port 8443}
         (tomcat/connector :port 8443))))

(deftest ssl-jsse-connector-test
  (is (= {::tc/pallet-type ::tc/connector
          :maxThreads 150 :protocol "HTTP/1.1" :scheme "https"
          :keystorePass "changeit" :sslProtocol "TLS" :clientAuth "false"
          :SSLEnabled "true" :port 8442 :secure "true"
          :keystoreFile "${user.home}/.keystore"}
         (tomcat/ssl-jsse-connector :port 8442))))

(deftest ssl-apr-connector-test
  (is (= {::tc/pallet-type ::tc/connector
          :maxThreads 150 :protocol "HTTP/1.1" :scheme "https"
          :sslProtocol "TLSv1" :clientAuth "optional" :SSLEnabled "true"
          :port 8442 :secure "true"
          :SSLCertificateKeyFile= "/usr/local/ssl/server.pem"
          :SSLCertificateFile "/usr/local/ssl/server.crt"}
         (tomcat/ssl-apr-connector :port 8442))))

(deftest listener-test
  (is (= {::tc/pallet-type ::tc/listener
          :className "org.apache.catalina.core.JasperListener"}
         (tomcat/listener :jasper))))

(deftest global-resources-test
  (is (= {::tc/pallet-type ::tc/global-resources}
         (tomcat/global-resources))))

(deftest host-test
  (is (= {::tc/pallet-type ::tc/host :name "localhost" :appBase "webapps"
          ::tc/valve [{::tc/pallet-type ::tc/valve
                       :className "org.apache.catalina.valves.RequestDumperValve"}]
          ::tc/alias [{::tc/pallet-type ::tc/alias :name "www.domain.com"}]}
         (tomcat/host "localhost" "webapps"
                      (tomcat/alias "www.domain.com")
                      (tomcat/valve :request-dumper)))))

(deftest service-test
  (is (= {::tc/pallet-type ::tc/service
          ::tc/connector
          [{::tc/pallet-type ::tc/connector :redirectPort "8443" :connectionTimeout "20000"
            :port "8080" :protocol "HTTP/1.1"}]
          ::tc/engine {::tc/pallet-type ::tc/engine :defaultHost "host" :name "catalina"
                       ::tc/valve
                       [{::tc/pallet-type ::tc/valve
                         :className "org.apache.catalina.valves.RequestDumperValve"}]}}
         (tomcat/service
          (tomcat/engine "catalina" "host"
                         (tomcat/valve :request-dumper))
          (tomcat/connector :port "8080" :protocol "HTTP/1.1"
                            :connectionTimeout "20000"
                            :redirectPort "8443")))))

(deftest server-test
  (is (= {::tc/pallet-type ::tc/server :port "123", :shutdown "SHUTDOWNx"
          ::tc/global-resources {::tc/pallet-type ::tc/global-resources}
          ::tc/listener
          [{::tc/pallet-type ::tc/listener :className "org.apache"}
           {::tc/pallet-type ::tc/listener
            :className "org.apache.catalina.core.JasperListener"}]}
         (tomcat/server
          :port "123" :shutdown "SHUTDOWNx"
          (tomcat/listener "org.apache")
          (tomcat/listener :jasper)
          (tomcat/global-resources))))
  (is (= {::tc/pallet-type ::tc/server :port "123" :shutdown "SHUTDOWNx"
          ::tc/service
          [{::tc/pallet-type ::tc/service
            ::tc/connector
            [{::tc/pallet-type ::tc/connector :redirectPort "8443"
              :connectionTimeout "20000" :port "8080" :protocol "HTTP/1.1"}]
            ::tc/engine
            {::tc/pallet-type ::tc/engine :defaultHost "host" :name "catalina"
             ::tc/host [{::tc/pallet-type ::tc/host, :name "localhost", :appBase "webapps"}]
             ::tc/valve [{::tc/pallet-type ::tc/valve :className
                       "org.apache.catalina.valves.RequestDumperValve"}]}}]
          ::tc/global-resources {::tc/pallet-type ::tc/global-resources}}
         (tomcat/server
          :port "123" :shutdown "SHUTDOWNx"
          (tomcat/global-resources)
          (tomcat/service
           (tomcat/engine
            "catalina" "host"
            (tomcat/valve :request-dumper)
            (tomcat/host "localhost" "webapps"))
           (tomcat/connector :port "8080" :protocol "HTTP/1.1"
                             :connectionTimeout "20000"
                             :redirectPort "8443"))))))


(deftest tomcat-server-xml-test
  (let [test-node (core/group-spec "test-node" :image  {:os-family :ubuntu})]
    (is (= "<?xml version='1.0' encoding='utf-8'?>\n<Server shutdown=\"SHUTDOWNx\" port=\"123\">\n  <Listener className=\"org.apache.catalina.core.JasperListener\"></Listener>\n  <Listener className=\"org.apache.catalina.mbeans.ServerLifecycleListener\"></Listener>\n  <Listener className=\"org.apache.catalina.mbeans.GlobalResourcesLifecycleListener\"></Listener>\n  <GlobalNamingResources>\n    <Resource pathname=\"conf/tomcat-users.xml\" factory=\"org.apache.catalina.users.MemoryUserDatabaseFactory\" description=\"User database that can be updated and saved\" type=\"org.apache.catalina.UserDatabase\" auth=\"Container\" name=\"UserDatabase\"></Resource>\n  </GlobalNamingResources>\n\n  <Service name=\"Catalina\">\n    <Connector URIEncoding=\"UTF-8\" redirectPort=\"8443\" connectionTimeout=\"20000\" protocol=\"HTTP/1.1\" port=\"8080\"></Connector>\n    <Engine defaultHost=\"localhost\" name=\"Catalina\">\n      <Realm resourceName=\"UserDatabase\" className=\"org.apache.catalina.realm.UserDatabaseRealm\"></Realm>\n\n      <Host xmlNamespaceAware=\"false\" xmlValidation=\"false\" deployOnStartup=\"true\" autoDeploy=\"true\" unpackWARs=\"true\" appBase=\"webapps\" name=\"localhost\">\n\n\t<Valve resolveHosts=\"false\" pattern=\"common\" suffix=\".log\" prefix=\"localhost_access.\" directory=\"logs\" className=\"org.apache.catalina.valves.AccessLogValve\"></Valve>\n      </Host>\n    </Engine>\n  </Service>\n</Server>"
           (apply str (tomcat/tomcat-server-xml
                       {:server test-node}
                       (tomcat/server :port "123" :shutdown "SHUTDOWNx"))))
        "Listener, GlobalNaminResources and Service should be taken from template")
    (is (= "<?xml version='1.0' encoding='utf-8'?>\n<Server shutdown=\"SHUTDOWNx\" port=\"123\"><GlobalNamingResources></GlobalNamingResources><Listener className=\"org.apache\"></Listener><Listener className=\"org.apache.catalina.core.JasperListener\"></Listener>\n  \n  \n  \n  \n\n  <Service name=\"Catalina\">\n    <Connector URIEncoding=\"UTF-8\" redirectPort=\"8443\" connectionTimeout=\"20000\" protocol=\"HTTP/1.1\" port=\"8080\"></Connector>\n    <Engine defaultHost=\"localhost\" name=\"Catalina\">\n      <Realm resourceName=\"UserDatabase\" className=\"org.apache.catalina.realm.UserDatabaseRealm\"></Realm>\n\n      <Host xmlNamespaceAware=\"false\" xmlValidation=\"false\" deployOnStartup=\"true\" autoDeploy=\"true\" unpackWARs=\"true\" appBase=\"webapps\" name=\"localhost\">\n\n\t<Valve resolveHosts=\"false\" pattern=\"common\" suffix=\".log\" prefix=\"localhost_access.\" directory=\"logs\" className=\"org.apache.catalina.valves.AccessLogValve\"></Valve>\n      </Host>\n    </Engine>\n  </Service>\n</Server>"
           (apply str
                  (tomcat/tomcat-server-xml
                   {:server test-node}
                   (tomcat/server
                    :port "123" :shutdown "SHUTDOWNx"
                    (tomcat/listener "org.apache")
                    (tomcat/listener :jasper)
                    (tomcat/global-resources)))))
        "Listener, GlobalNamingResources and Service should be taken from args")

    (is (= "<?xml version='1.0' encoding='utf-8'?>\n<Server shutdown=\"SHUTDOWNx\" port=\"123\"><GlobalNamingResources><Transaction factory=\"some.transaction.class\"></Transaction><resource></resource><Environment name=\"name\" value=\"1\" type=\"java.lang.Integer\"></Environment></GlobalNamingResources>\n  <Listener className=\"org.apache.catalina.core.JasperListener\"></Listener>\n  <Listener className=\"org.apache.catalina.mbeans.ServerLifecycleListener\"></Listener>\n  <Listener className=\"org.apache.catalina.mbeans.GlobalResourcesLifecycleListener\"></Listener>\n  \n\n  <Service name=\"Catalina\">\n    <Connector URIEncoding=\"UTF-8\" redirectPort=\"8443\" connectionTimeout=\"20000\" protocol=\"HTTP/1.1\" port=\"8080\"></Connector>\n    <Engine defaultHost=\"localhost\" name=\"Catalina\">\n      <Realm resourceName=\"UserDatabase\" className=\"org.apache.catalina.realm.UserDatabaseRealm\"></Realm>\n\n      <Host xmlNamespaceAware=\"false\" xmlValidation=\"false\" deployOnStartup=\"true\" autoDeploy=\"true\" unpackWARs=\"true\" appBase=\"webapps\" name=\"localhost\">\n\n\t<Valve resolveHosts=\"false\" pattern=\"common\" suffix=\".log\" prefix=\"localhost_access.\" directory=\"logs\" className=\"org.apache.catalina.valves.AccessLogValve\"></Valve>\n      </Host>\n    </Engine>\n  </Service>\n</Server>"
           (apply str
                  (tomcat/tomcat-server-xml
                   {:server test-node}
                   (tomcat/server
                    :port "123" :shutdown "SHUTDOWNx"
                    (tomcat/global-resources
                     (tomcat/environment "name" 1 Integer)
                     (tomcat/resource
                      "jdbc/mydb" :sql-data-source :description "my db")
                     (tomcat/transaction "some.transaction.class"))))))
        "Listener, GlobalNamingResources and Service should be taken from args")))

(deftest server-configuration-test
  (is (= (first
          (build-actions/build-actions
           {}
           (directory/directory "/var/lib/tomcat6/conf")
           (remote-file/remote-file
            "/var/lib/tomcat6/conf/server.xml"
            :content "<?xml version='1.0' encoding='utf-8'?>\n<Server shutdown=\"SHUTDOWNx\" port=\"123\"><GlobalNamingResources></GlobalNamingResources><Listener className=\"\"></Listener>\n  \n  \n  \n  \n\n  <Service name=\"Catalina\">\n    <Connector URIEncoding=\"UTF-8\" redirectPort=\"8443\" connectionTimeout=\"20000\" protocol=\"HTTP/1.1\" port=\"80\"></Connector>\n    <Engine defaultHost=\"host\" name=\"catalina\"><Valve className=\"org.apache.catalina.valves.RequestDumperValve\"></Valve>\n      <Realm resourceName=\"UserDatabase\" className=\"org.apache.catalina.realm.UserDatabaseRealm\"></Realm>\n\n      <Host xmlNamespaceAware=\"false\" xmlValidation=\"false\" deployOnStartup=\"true\" autoDeploy=\"true\" unpackWARs=\"true\" appBase=\"webapps\" name=\"localhost\">\n\n\t\n      </Host>\n    </Engine>\n  </Service>\n</Server>"
            :flag-on-changed tomcat/tomcat-config-changed-flag)))
         (first
          (build-actions/build-actions
           {:parameters {:host {:id {:tomcat {:base "/var/lib/tomcat6/"}}}}}
           (tomcat/server-configuration
            (tomcat/server
             :port "123" :shutdown "SHUTDOWNx"
             (tomcat/listener :global-resources-lifecycle)
             (tomcat/global-resources)
             (tomcat/service
              (tomcat/engine
               "catalina" "host" (tomcat/valve :request-dumper))
              (tomcat/connector
               :port "80" :protocol "HTTP/1.1" :connectionTimeout "20000"
               :redirectPort "8443"))))))))
  (is (= (first
          (build-actions/build-actions
           {}
           (directory/directory "/var/lib/tomcat6/conf")
           (remote-file/remote-file
            "/var/lib/tomcat6/conf/server.xml"
            :content "<?xml version='1.0' encoding='utf-8'?>\n<Server shutdown=\"SHUTDOWN\" port=\"8005\">\n  <Listener className=\"org.apache.catalina.core.JasperListener\"></Listener>\n  <Listener className=\"org.apache.catalina.mbeans.ServerLifecycleListener\"></Listener>\n  <Listener className=\"org.apache.catalina.mbeans.GlobalResourcesLifecycleListener\"></Listener>\n  <GlobalNamingResources>\n    <Resource pathname=\"conf/tomcat-users.xml\" factory=\"org.apache.catalina.users.MemoryUserDatabaseFactory\" description=\"User database that can be updated and saved\" type=\"org.apache.catalina.UserDatabase\" auth=\"Container\" name=\"UserDatabase\"></Resource>\n  </GlobalNamingResources>\n\n  <Service name=\"Catalina\">\n    <Connector URIEncoding=\"UTF-8\" redirectPort=\"8443\" connectionTimeout=\"20000\" protocol=\"HTTP/1.1\" port=\"8080\"></Connector>\n    <Engine defaultHost=\"localhost\" name=\"Catalina\">\n      <Realm resourceName=\"UserDatabase\" className=\"org.apache.catalina.realm.UserDatabaseRealm\"></Realm>\n\n      <Host xmlNamespaceAware=\"false\" xmlValidation=\"false\" deployOnStartup=\"true\" autoDeploy=\"true\" unpackWARs=\"true\" appBase=\"webapps\" name=\"localhost\">\n\n\t<Valve resolveHosts=\"false\" pattern=\"common\" suffix=\".log\" prefix=\"localhost_access.\" directory=\"logs\" className=\"org.apache.catalina.valves.AccessLogValve\"></Valve>\n      </Host>\n    </Engine>\n  </Service>\n</Server>"
            :flag-on-changed tomcat/tomcat-config-changed-flag)))
         (first
          (build-actions/build-actions
           {:parameters {:host {:id {:tomcat {:base "/var/lib/tomcat6/"}}}}}
           (tomcat/server-configuration
            (tomcat/server)))))))

(deftest invoke-test
  (is (build-actions/build-actions
       {:blobstore (blobstore/service "url-blobstore")
        :environment {:blobstore (blobstore/service "url-blobstore")}}
       (tomcat/tomcat)
       (tomcat/tomcat :version 6)
       (tomcat/tomcat :version "tomcat-6-1.2")
       (tomcat/undeploy "app")
       (tomcat/undeploy-all)
       (tomcat/deploy "app" :content "")
       (tomcat/deploy "app" :blob {:container "c" :path "p"})
       (tomcat/policy 1 "name" {})
       (tomcat/application-conf "name" "content")
       (tomcat/user "name" {:password "pwd"})
       (tomcat/server-configuration (tomcat/server)))))

(def
  ^{:doc "An html file for tomcat to server to verify we have it running."}
  index-html
  "<html>
<head>
  <title>Pallet-live-test</title>
  <%@ page language=\"java\" %>
</head>
<body>
<h3><%= java.net.InetAddress.getLocalHost().getHostAddress() %></h2>
<h3><%= java.lang.System.getProperty(\"java.vendor\") %></h2>
LIVE TEST
</body></html>")

(def
  ^{:doc "An application configuration context"}
  application-config
  "<?xml version=\"1.0\" encoding=\"utf-8\"?>
<Context
privileged=\"true\"
path=\"/pallet-live-test\"
swallowOutput=\"true\">
</Context>")

(deftest live-test
  (live-test/test-for
   [image live-test/*images*]
   (live-test/test-nodes
    [compute node-map node-types]
    {:tomcat
     {:image image
      :count 1
      :phases {:bootstrap (phase/phase-fn
                           (automated-admin-user/automated-admin-user))
               :configure (phase/phase-fn
                           (tomcat/install :version 6)
                           (tomcat/server-configuration (server))
                           (tomcat/application-conf
                            "pallet-live-test" application-config)
                           (thread-expr/let-with-arg->
                             request
                             [tomcat-base (parameter/get-for-target
                                           request [:tomcat :base])]
                             (directory/directory
                              (str tomcat-base "webapps/pallet-live-test/"))
                             (remote-file/remote-file
                              (str
                               tomcat-base "webapps/pallet-live-test/index.jsp")
                              :content index-html :literal true
                              :flag-on-changed
                              tomcat/tomcat-config-changed-flag))
                            (tomcat/init-service
                             :if-config-changed true :action :restart))
                :verify (phase/phase-fn
                         (exec-script/exec-checked-script
                          "check tomcat is running"
                          (pipe
                           (wget
                            "-O-" "http://localhost:8080/pallet-live-test/")
                           (grep (quoted "LIVE TEST")))))}}}
     (core/lift (:tomcat node-types) :phase :verify :compute compute))))

(deftest live-sun-jdk-test
  (live-test/test-for
   [image live-test/*images*]
   (live-test/test-nodes
    [compute node-map node-types]
    {:tomcatsun
     {:image image
      :count 1
      :phases {:bootstrap (resource/phase
                           (automated-admin-user/automated-admin-user))
               :configure (fn [request]
                            (->
                             request
                             (install :version 6)
                             (remote-file/remote-file
                              "jdk.bin"
                              :local-file
                              (if (compute/is-64bit? (:target-node request))
                                "artifacts/jdk-6u23-linux-x64-rpm.bin"
                                "artifacts/jdk-6u24-linux-i586-rpm.bin")
                              :mode "755")
                             (java/java :sun :jdk :rpm-bin "./jdk.bin")
                             (server-configuration (server))
                             (application-conf
                              "pallet-live-test" application-config)
                             (thread-expr/let-with-arg->
                               request
                               [tomcat-base (parameter/get-for-target
                                             request [:tomcat :base])]
                               (directory/directory
                                (str tomcat-base "webapps/pallet-live-test/"))
                               (remote-file/remote-file
                                (str
                                 tomcat-base
                                 "webapps/pallet-live-test/index.jsp")
                                :content index-html :literal true
                                :flag-on-changed tomcat-config-changed-flag))
                             (init-service
                              :if-config-changed true :action :restart)))
               :verify (resource/phase
                        (exec-script/exec-checked-script
                         "check tomcat is running"
                         (pipe
                          (wget "-O-" "http://localhost:8080/pallet-live-test/")
                          (grep -i (quoted "sun")))))}}}
    (core/lift (:tomcatsun node-types) :phase :verify :compute compute))))
