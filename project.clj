(use '[clojure.java.shell :only (sh)])
(require '[clojure.string :as string])

(defn git-ref
  []
  (or (System/getenv "GIT_COMMIT")
      (string/trim (:out (sh "git" "rev-parse" "HEAD")))
      ""))

(defproject org.iplantc/clockwork "4.0.0"
  :description "Scheduled jobs for the iPlant Discovery Environment"
  :url "http://www.iplantcollaborative.org"
  :license {:name "BSD"
            :url "http://iplantcollaborative.org/sites/default/files/iPLANT-LICENSE.txt"}
  :scm {:connection "scm:git:git@github.com:iPlantCollaborativeOpenSource/clockwork.git"
        :developerConnection "scm:git:git@github.com:iPlantCollaborativeOpenSource/clockwork.git"
        :url "git@github.com:iPlantCollaborativeOpenSource/clockwork.git"}
  :manifest {"Git-Ref" ~(git-ref)}
  :pom-addition [:developers
                 [:developer
                  [:url "https://github.com/orgs/iPlantCollaborativeOpenSource/teams/iplant-devs"]]]
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/java.jdbc "0.2.3"]
                 [org.clojure/tools.logging "0.2.3"]
                 [cheshire "5.0.2"]
                 [clj-http "0.6.5"]
                 [clj-time "0.4.5"]
                 [clojurewerkz/quartzite "1.0.1"]
                 [com.cemerick/url "0.0.7"]
                 [korma "0.3.0-RC2"]
                 [log4j "1.2.17"]
                 [org.iplantc/clj-jargon "4.0.0"
                  :exclusions [[org.irods.jargon.transfer/jargon-transfer-dao-spring]]]
                 [org.iplantc/clojure-commons "4.0.0"]
                 [org.iplantc/common-cli "4.0.0"]
                 [org.iplantc/kameleon "4.0.0"]
                 [me.raynes/fs "1.4.4"]
                 [org.slf4j/slf4j-api "1.7.2"]
                 [org.slf4j/slf4j-log4j12 "1.6.6"]
                 [slingshot "0.10.3"]]
  :plugins [[org.iplantc/lein-iplant-rpm "4.0.0"]]
  :profiles {:dev {:resource-paths ["resources/test"]}}
  :aot [clockwork.core]
  :main clockwork.core
  :iplant-rpm {:summary "Scheduled jobs for the iPlant Discovery Environment"
               :provides "clockwork"
               :dependencies ["iplant-service-config >= 0.1.0-5"
                              "iplant-clavin"
                              "java-1.7.0-openjdk"]
               :config-files ["log4j.properties"]
               :config-path "resources/main"}
  :uberjar-exclusions [#"BCKEY.SF"]
  :repositories [["sonatype-nexus-snapshots"
                  {:url "https://oss.sonatype.org/content/repositories/snapshots"}]]
  :deploy-repositories [["sonatype-nexus-staging"
                         {:url "https://oss.sonatype.org/service/local/staging/deploy/maven2/"}]])
