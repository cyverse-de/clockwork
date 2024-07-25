(use '[clojure.java.shell :only (sh)])
(require '[clojure.string :as string])

(defn git-ref
  []
  (or (System/getenv "GIT_COMMIT")
      (string/trim (:out (sh "git" "rev-parse" "HEAD")))
      ""))

(defproject org.cyverse/clockwork "2.10.0-SNAPSHOT"
  :description "Scheduled jobs for the CyVerse Discovery Environment"
  :url "https://github.com/cyverse-de/clockwork"
  :license {:name "BSD"
            :url "https://cyverse.org/license"}
  :manifest {"Git-Ref" ~(git-ref)}
  :uberjar-name "clockwork-standalone.jar"
  :dependencies [[org.clojure/clojure "1.11.3"]
                 [cheshire "5.13.0"
                   :exclusions [[com.fasterxml.jackson.dataformat/jackson-dataformat-cbor]
                                [com.fasterxml.jackson.dataformat/jackson-dataformat-smile]
                                [com.fasterxml.jackson.core/jackson-annotations]
                                [com.fasterxml.jackson.core/jackson-databind]
                                [com.fasterxml.jackson.core/jackson-core]]]
                 [clj-time "0.15.2"]
                 [clojurewerkz/quartzite "1.0.1"
                   :exclusions [c3p0]]
                 [com.mchange/c3p0 "0.10.1"]
                 [com.novemberain/langohr "3.5.1"]
                 [org.cyverse/clojure-commons "3.0.8"]
                 [org.cyverse/common-cli "2.8.2"]
                 [org.cyverse/service-logging "2.8.4"]
                 [me.raynes/fs "1.4.6"]
                 [slingshot "0.12.2"]]
  :profiles {:dev     {:resource-paths ["resources/test"]
                       :jvm-opts       ["-Dotel.javaagent.enabled=false"]}
             :test    {:resource-paths ["resources/test"]}
             :uberjar {:aot :all}}
  :main ^:skip-aot clockwork.core
  :plugins [[jonase/eastwood "1.4.2"]
            [lein-ancient "0.7.0"]
            [test2junit "1.4.4"]]
  :uberjar-exclusions [#"BCKEY.SF"]
  :jvm-opts ["-Dlogback.configurationFile=/etc/iplant/de/logging/clockwork-logging.xml" "-javaagent:./opentelemetry-javaagent.jar" "-Dotel.resource.attributes=service.name=clockwork"])
