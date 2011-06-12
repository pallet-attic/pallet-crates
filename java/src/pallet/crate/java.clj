(ns pallet.crate.java
  "Crates for java 1.6 installation and configuration.

   Sun Java installation on CentOS requires use of Oracle rpm's. Download from
   http://www.oracle.com/technetwork/java/javase/downloads/index.html and get
   the .rpm.bin file onto the node with remote-file.  Then pass the location of
   the rpm.bin file on the node using the :rpm-bin option. The rpm will be
   installed."
  (:require
   [pallet.action :as action]
   [pallet.action.environment :as environment]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.package :as package]
   [pallet.action.package.jpackage :as jpackage]
   [pallet.action.remote-file :as remote-file]
   [pallet.parameter :as parameter]
   [pallet.script :as script]
   [pallet.script.lib :as lib]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore]
   [pallet.thread-expr :as thread-expr]
   [clojure.string :as string])
  (:use pallet.thread-expr))

(def vendor-keywords #{:openjdk :sun})
(def component-keywords #{:jdk :jre :bin})
(def all-keywords (into #{} (concat vendor-keywords component-keywords)))

(def deb-package-names
  {:openjdk "openjdk-6-"
   :sun "sun-java6-"})

(def yum-package-names
  {:openjdk "java-1.6.0-openjdk"})

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
  (when-let [base (yum-package-names vendor)]
    [(str base ({:jdk "-devel" :jre ""} component ""))]))

(defmethod java-package-name :pacman [mgr vendor component]
  (if (= :sun vendor)
    [component]
    [(pacman-package-names vendor)]))

(def ubuntu-partner-url "http://archive.canonical.com/ubuntu")

(defn- use-jpackage
  "Determine if jpackage should be used"
  [session]
  (#{:centos :rhel :fedora} (session/os-family session)))

(defn- use-alternatives
  "Determine if alternatives should be used"
  [session]
  nil)


(defn rpm-bin-file
  "Upload an rpm bin file for java. Options are as for remote-file"
  [session filename & {:as options}]
  (-> session
      (action/with-precedence {:action-id ::upload-rpm-bin
                               :always-before ::unpack-sun-rpm}
        (thread-expr/apply-map->
         remote-file/remote-file
         filename
         (merge
          {:local-file-options
           {:always-before #{`unpack-sun-rpm}}
           :mode "755"}
          options)))))

;; this is an action so we can declare :always-before package
(defn unpack-sun-rpm
  "Unpack the sun binary rpm."
  [session rpm-path]
  (->
   session
   (action/with-precedence {:action-id ::unpack-sun-rpm}
     (exec-script/exec-checked-script
      (format "Unpack java rpm %s" rpm-path)
      (~lib/heredoc "java-bin-resp" "A\n\n" {})
      (chmod "+x" ~rpm-path)
      (~rpm-path < "java-bin-resp")))))

(defn make-compat
  [session update]
  ;; arch is hard coded to i586 for ix86 in the spec file
  (let [arch (stevedore/script
              (pipe (~lib/arch) (sed -e (quoted "s/[1-6]86/586/"))))
        pkg (format "java-1.6.0-sun-compat-1.6.0.%s-1jpp.%s" update arch)]
    (->
     session
     (remote-file/remote-file
      "java-1.6.0-sun-compat-1.6.0.03-1jpp.src.rpm"
      :url "http://mirrors.dotsrc.org/jpackage/5.0/generic/non-free/SRPMS/java-1.6.0-sun-compat-1.6.0.03-1jpp.src.rpm")
     (package/package "rpm-build")
     (package/package "libxslt")
     (exec-script/exec-checked-script
      "rebuild source rpm"
      (if-not (rpm -q ~pkg > "/dev/null" "2>&1")
        (do
          (rpm -ivh "java-1.6.0-sun-compat-1.6.0.03-1jpp.src.rpm")
          (cd "/usr/src/redhat/SPECS")
          (~lib/sed-file
           "java-1.6.0-sun-compat.spec"
           ~{"buildver.*03" (str "buildver " (format "%s" update))}
           {})

          (sed
           -i -e
           (quoted
            (str "\\_%config.*/lib/security/java.security_ i \\\n"
                 "%config(noreplace) %{_jvmdir}/%{jredir}/lib/security/blacklist")
            " \n")
           -e
           (quoted
            (str "\\_%config.*/lib/security/java.security_ i \\\n"
                 "%config(noreplace) %{_jvmdir}/%{jredir}/lib/security/javaws.policy")
            " \n")
           -e
           (quoted
            (str
             "\\_%config.*/lib/security/java.security_ i \\\n"
             "%config(noreplace) %{_jvmdir}/%{jredir}/lib/security/trusted.libraries")
            " \n")
           "java-1.6.0-sun-compat.spec")
          (rpmbuild -ba java-1.6.0-sun-compat.spec)
          (rpm -Uvh ~(str "/usr/src/redhat/RPMS/" arch "/" pkg ".rpm"))))))))

(def sun-paths
  {:jdk {:jre "/usr/java/%s%s/jre"
         :jdk "/usr/java/%s%s"
         :jdk-bin "/usr/java/%s%s/bin/"
         :jre-bin "/usr/java/%s%s/jre/bin/"
         :jce_local_policy "/usr/java/%s%s/jre/lib/security/local_policy.jar"}
   :jre {:jre "/usr/java/%s%s/jre"
         :jre-bin "/usr/java/%s%s/jre/bin/"
         :jce_local_policy "/usr/java/%s%s/jre/lib/security/local_policy.jar"}})

(defn sun-version
  "Extract the sun version from the rpm file name.
       (sun-version \"jdk-6u23-linux-x64-rpm.bin\")
         ==> '(:jdk \"6\" \"23\")"
  [rpm]
  (let [file (java.io.File. rpm)
        filename (.getName file)]
    (vec
     (concat
      [(keyword (second (re-find #"(j..)-"  filename)))]
      ((juxt second #(nth % 2))
       (re-find #"j..-([0-9]+)u([0-9]+)-" filename))))))

(def slave-binaries
  {:jdk [:appletviewer :apt :extcheck :HtmlConverter :idlj :jar
         :jarsigner :javadoc :javah :javap :jconsole :jdb :jhat :jinfo :jmap
         :jps :jrunscript :jsadebugd :jstack :jstat :jstatd :jnative2:ascii
         :rmic :schemagen :serialver :wsgen :wsimport :xjc]
   :jre [:javaws :keytool :orbd :pack200 :rmid :rmiregistry
         :servertool :tnameserv :unpack200]})

(defn sun-alternatives
  [[component major update]]
  (let [version (format "1.%s.%s_%s" major 0 update)
        priority (format "1%s%s0" major update)
        jdk-bin (format
                 (:jdk-bin (sun-paths component)) (name component) version)
        jre-bin (format
                 (:jre-bin (sun-paths component)) (name component) version)
        jdk-binary (fn [prog] (str jdk-bin (name prog)))
        jre-binary (fn [prog] (str jre-bin (name prog)))]
    (stevedore/checked-script
     "Set alternatives"
     ~(if (= :jdk component)
        (stevedore/chained-script
         (alternatives
          --install "/usr/bin/javac" javac ~(jdk-binary :javac) ~priority
          ~(string/join
            " "
            (map
             (fn [prog]
               (stevedore/script
                (--slave
                 ~(format "/usr/bin/%s" (name prog))
                 ~(name prog) ~(jdk-binary prog))))
             (slave-binaries component))))
         (alternatives --auto javac)))
     ~(if (#{:jre :jdk} component)
        (stevedore/chained-script
         (alternatives
          --install "/usr/bin/java" java ~(jre-binary :java) ~priority
          ~(string/join
            " "
            (map
             (fn [prog]
               (stevedore/script
                (--slave
                 ~(format "/usr/bin/%s" (name prog))
                 ~(name prog) ~(jre-binary prog))))
             (slave-binaries :jre))))
         (alternatives --auto java))))))


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

(defn java
  "Install java. Options can be :sun, :openjdk, :jdk, :jre.
   By default openjdk will be installed.

   On CentOS, when specifying :sun, you can also pass the path of the
   Oracle rpm.bin file to the :rpm-bin option, and the rpm will be installed."
  [session & options]
  (let [vendors (or (seq (filter vendor-keywords options))
                    [:sun])
        components (into #{} (or (seq (filter #{:jdk :jre} options))
                                 #{:jdk}))
        packager (session/packager session)
        os-family (session/os-family session)
        use-jpackage (use-jpackage session)
        use-alternatives (use-alternatives session)
        rpm-bin (:rpm-bin (apply hash-map (remove all-keywords options)))]
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
               (when->
                rpm-bin
                (unpack-sun-rpm rpm-bin)
                (when->
                 use-alternatives
                 (arg->
                  [request]
                  (exec-script/exec-checked-script
                   "Set alternatives for java"
                   ~(sun-alternatives (sun-version rpm-bin))))))
               (when->
                use-jpackage
                (arg->
                 [request]
                 (action/with-precedence
                   {:action-id ::install-java-compat
                    :always-after
                    :pallet.action.package.jpackage/install-jpackage-compat}
                   (make-compat (last (sun-version rpm-bin)))))))
       (package/package-manager :update)
       (for-> [vendor vendors]
              (for-> [component components]
                     (vc vendor component)))
       (when->
        (components :jdk)
        (environment/system-environment
         "java"
         {"JAVA_HOME" (stevedore/script (~jdk-home))}))
       (when->
        (and (components :jre) (not (components :jdk)))
        (environment/system-environment
         "java"
         {"JAVA_HOME" (stevedore/script (~java-home))}))))))

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
