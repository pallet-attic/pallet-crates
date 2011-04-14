(ns pallet.crate.java
  "Crates for java 1.6 installation and configuration.

   Sun Java installation on CentOS requires use of Oracle rpm's. Download from
   http://www.oracle.com/technetwork/java/javase/downloads/index.html and get
   the .rpm.bin file onto the node with remote-file.  Then pass the location of
   the rpm.bin file on the node using the :rpm-bin option. The rpm will be
   installed."
  (:require
   [pallet.request-map :as request-map]
   [pallet.resource :as resource]
   [pallet.resource.exec-script :as exec-script]
   [pallet.resource.package :as package]
   [pallet.resource.remote-file :as remote-file]
   [pallet.script :as script]
   [pallet.stevedore :as stevedore]
   [pallet.target :as target])
  (:use pallet.thread-expr))

(def vendor-keywords #{:openjdk :sun})
(def component-keywords #{:jdk :jre :bin})
(def all-keywords (into #{} (concat vendor-keywords component-keywords)))

(def deb-package-names
  {:openjdk "openjdk-6-"
   :sun "sun-java6-"})

(def yum-package-names
  {:openjdk "java"})

(def pacman-package-names
  {:openjdk "openjdk6"})

(defmulti java-package-name "lookup package name"
  (fn [mgr vendor component] mgr))

(defmethod java-package-name :aptitude [mgr vendor component]
  (if (= vendor :sun)
    [(str (deb-package-names vendor) "bin")
     (str (deb-package-names vendor) (name component))]
    [(str (deb-package-names vendor) (name component))]))

(defmethod java-package-name :yum [mgr vendor component]
  [(yum-package-names vendor)])

(defmethod java-package-name :pacman [mgr vendor component]
  (if (= :sun vendor)
    [component]
    [(pacman-package-names vendor)]))

(def ubuntu-partner-url "http://archive.canonical.com/ubuntu")

(defn- use-jpackage
  "Determine if jpackage should be used"
  [request]
  (let [os-family (request-map/os-family request)]
    (and
     (= :centos os-family)
     (re-matches #"5\.[0-5]" (request-map/os-version request)))))

(defn java
  "Install java. Options can be :sun, :openjdk, :jdk, :jre.
   By default openjdk will be installed.

   On CentOS, when specifying :sun, you can also pass the path of the
   Oracle rpm.bin file to the :rpm-bin option, and the rpm will be installed."
  [request & options]
  (let [vendors (or (seq (filter vendor-keywords options))
                    [:sun])
        components (or (seq (filter #{:jdk :jre} options))
                       [:jdk])
        packager (:target-packager request)
        os-family (request-map/os-family request)
        use-jpackage (use-jpackage request)]
    (let [vc (fn [request vendor component]
               (let [pkgs (java-package-name packager vendor component)]
                 (->
                  request
                  (for->
                   [p pkgs]
                   (when-> (and (= packager :aptitude) (= vendor :sun))
                           (package/package-manager
                            :debconf
                            (str
                             p " shared/present-sun-dlj-v1-1 note")
                            (str
                             p " shared/accepted-sun-dlj-v1-1 boolean true")))
                   (package/package p)))))]
      (->
       request
       (when-> (some #(= :sun %) vendors)
               (when-> (= packager :aptitude)
                       (when-> (= os-family :ubuntu)
                               (package/package-source
                                "Partner"
                                :aptitude {:url ubuntu-partner-url
                                           :scopes ["partner"]})
                               (package/package-manager :universe)
                               (package/package-manager :multiverse)
                               (package/package-manager :update))
                       (when-> (= os-family :debian)
                               (package/package-manager
                                :add-scope :scope :non-free)
                               (package/package-manager :update)))
               (when-> use-jpackage
                (package/add-jpackage)
                (package/package-manager-update-jpackage)
                (package/jpackage-utils)
                (when-let->
                 [rpm-bin (:rpm-bin
                           (apply hash-map (remove all-keywords options)))]
                 (exec-script/exec-checked-script
                  "Unpack java rpm"
                  (heredoc "java-bin-resp" "A\n\n")
                  (chmod "+x" ~rpm-bin)
                  (~rpm-bin < "java-bin-resp"))
                 (package/package
                  "java-1.6.0-sun-compat"
                  :enable ["jpackage-generic" "jpackage-generic-updates"]))))
       (package/package-manager :update)
       (for-> [vendor vendors]
              (for-> [component components]
                     (vc vendor component)))))))

(script/defscript java-home [])
(stevedore/defimpl java-home :default []
  @("dirname" @("dirname" @("readlink" -f @("which" java)))))
(stevedore/defimpl java-home [#{:aptitude}] []
  @("dirname" @("dirname" @("update-alternatives" --list java))))
(stevedore/defimpl java-home [#{:darwin :os-x}] []
   @JAVA_HOME)

(script/defscript jdk-home [])
(stevedore/defimpl jdk-home :default []
  @("dirname" @("dirname" @("readlink" -f @("which" javac)))))
(stevedore/defimpl jdk-home [#{:aptitude}] []
  @("dirname" @("dirname" @("update-alternatives" --list javac))))
(stevedore/defimpl jdk-home [#{:darwin :os-x}] []
   @JAVA_HOME)

(script/defscript jre-lib-security [])
(stevedore/defimpl jre-lib-security :default []
  (str @(update-java-alternatives -l "|" cut "-d ' '" -f 3 "|" head -1)
       "/jre/lib/security/"))

(defn jce-policy-file
  "Installs a local JCE policy jar at the given path in the remote JAVA_HOME's
   lib/security directory, enabling the use of \"unlimited strength\" crypto
   implementations. Options are as for remote-file.

   e.g. (jce-policy-file
          \"local_policy.jar\" :local-file \"path/to/local_policy.jar\")

   Note this only intended to work for ubuntu/aptitude-managed systems and Sun
   JDKs right now."
  [request filename & {:as options}]
  (apply remote-file/remote-file request
    (stevedore/script (str (jre-lib-security) ~filename))
    (apply
     concat (merge {:owner "root" :group "root" :mode 644} options))))
