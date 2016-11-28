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
            :url "http://iplantcollaborative.org/sites/default/files/iPLANT-LICENSE.txt"}
  :manifest {"Git-Ref" ~(git-ref)}
  :uberjar-name "clockwork-standalone.jar"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/java.jdbc "0.2.3"]
                 [cheshire "5.5.0"
                   :exclusions [[com.fasterxml.jackson.dataformat/jackson-dataformat-cbor]
                                [com.fasterxml.jackson.dataformat/jackson-dataformat-smile]
                                [com.fasterxml.jackson.core/jackson-annotations]
                                [com.fasterxml.jackson.core/jackson-databind]
                                [com.fasterxml.jackson.core/jackson-core]]]
                 [clj-http "2.0.0"]
                 [clj-time "0.4.5"]
                 [clojurewerkz/quartzite "1.0.1"
                   :exclusions [c3p0]]
                 [com.cemerick/url "0.0.7"]
                 [com.mchange/c3p0 "0.9.5.1"]
                 [com.novemberain/langohr "3.5.1"]
                 [korma "0.3.0-RC5"
                  :exclusions [c3p0]]
                 [org.cyverse/clojure-commons "2.8.0"]
                 [org.cyverse/common-cli "2.8.0"]
                 [org.cyverse/event-messages "0.0.1"]
                 [org.cyverse/kameleon "2.8.0"]
                 [org.cyverse/service-logging "2.8.0"]
                 [me.raynes/fs "1.4.6"]
                 [slingshot "0.10.3"]]
  :eastwood {:exclude-namespaces [apps.protocols :test-paths]
             :linters [:wrong-arity :wrong-ns-form :wrong-pre-post :wrong-tag :misplaced-docstrings]}
  :profiles {:dev     {:resource-paths ["resources/test"]}
             :test    {:resource-paths ["resources/test"]}
             :uberjar {:aot :all}}
  :main ^:skip-aot clockwork.core
  :plugins [[jonase/eastwood "0.2.3"]
            [test2junit "1.1.3"]]
  :uberjar-exclusions [#"BCKEY.SF"])
