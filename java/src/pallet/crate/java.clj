(ns pallet.crate.java
  "Crates for java 1.6 installation and configuration.

   Sun Java installation on CentOS requires use of Oracle rpm's. Download from
   http://www.oracle.com/technetwork/java/javase/downloads/index.html and get
   the .rpm.bin file onto the node with remote-file.  Then pass the location of
   the rpm.bin file on the node using the :rpm-bin option. The rpm will be
   installed."
  (:require
   [pallet.action :as action]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.package :as package]
   [pallet.action.package.jpackage :as jpackage]
   [pallet.action.remote-file :as remote-file]
   [pallet.script :as script]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore])
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
  [session]
  (let [os-family (session/os-family session)]
    (and
     (= :centos os-family)
     (re-matches #"5\.[0-5]" (session/os-version session)))))

(defn java
  "Install java. Options can be :sun, :openjdk, :jdk, :jre.
   By default openjdk will be installed.

   On CentOS, when specifying :sun, you can also pass the path of the
   Oracle rpm.bin file to the :rpm-bin option, and the rpm will be installed."
  [session & options]
  (let [vendors (or (seq (filter vendor-keywords options))
                    [:sun])
        components (or (seq (filter #{:jdk :jre} options))
                       [:jdk])
        packager (session/packager session)
        os-family (session/os-family session)
        use-jpackage (use-jpackage session)]
    (let [vc (fn [session vendor component]
               (let [pkgs (java-package-name packager vendor component)]
                 (->
                  session
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
       session
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
               (when->
                use-jpackage
                (jpackage/add-jpackage)
                (jpackage/package-manager-update-jpackage)
                (jpackage/jpackage-utils))
               (when-let->
                [rpm-bin (:rpm-bin
                          (apply hash-map (remove all-keywords options)))]
                (exec-script/exec-checked-script
                 "Unpack java rpm"
                 (heredoc "java-bin-resp" "A\n\n")
                 (chmod "+x" ~rpm-bin)
                 (~rpm-bin < "java-bin-resp")))
               (when->
                use-jpackage
                (package/package
                 "java-1.6.0-sun-compat"
                 :enable ["jpackage-generic" "jpackage-generic-updates"])))
       (package/package-manager :update)
       (for-> [vendor vendors]
              (for-> [component components]
                     (vc vendor component)))))))

(script/defscript java-home [])
(script/defimpl java-home :default []
  @("dirname" @("dirname" @("readlink" -f @("which" java)))))
(script/defimpl java-home [#{:aptitude}] []
  @("dirname" @("dirname" @("update-alternatives" --list java))))
(script/defimpl java-home [#{:darwin :os-x}] []
   @JAVA_HOME)

(script/defscript jdk-home [])
(script/defimpl jdk-home :default []
  @("dirname" @("dirname" @("readlink" -f @("which" javac)))))
(script/defimpl jdk-home [#{:aptitude}] []
  @("dirname" @("dirname" @("update-alternatives" --list javac))))
(script/defimpl jdk-home [#{:darwin :os-x}] []
   @JAVA_HOME)

(script/defscript jre-lib-security [])
(script/defimpl jre-lib-security :default []
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
  [session filename & {:as options}]
  (apply remote-file/remote-file session
    (stevedore/script (str (jre-lib-security) ~filename))
    (apply
     concat (merge {:owner "root" :group "root" :mode 644} options))))
