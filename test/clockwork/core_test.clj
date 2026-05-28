(ns clockwork.core-test
  (:use clojure.test)
  (:require [clockwork.config :as config]
            [clockwork.core :as core]
            [clojurewerkz.quartzite.scheduler :as qs]))

(defn with-empty-config [f]
  (require 'clockwork.config :reload)
  (config/load-config-from-file "resources/test/empty-config.properties")
  (f))

(use-fixtures :once with-empty-config)

(deftest test-config-defaults
  (testing "configuration defaults"
    (is (= (config/irods-host) "irods"))
    (is (= (config/irods-port) "1247"))
    (is (= (config/irods-user) "rods"))
    (is (= (config/irods-password) "notprod"))
    (is (= (config/irods-home) "/iplant/home"))
    (is (= (config/irods-zone) "iplant"))
    (is (= (config/irods-resource) ""))
    (is (= (config/infosquito-job-basename) "indexing.1"))
    (is (= (config/infosquito-job-daynum) 1))
    (is (= (config/amqp-uri) "amqp://guest:guest@rabbit:5672/"))
    (is (= (config/exchange-name) "de"))))

(deftest test-init-scheduler-returns-started-scheduler
  (testing "init-scheduler returns a started Quartz scheduler"
    (let [s (#'core/init-scheduler)]
      (try
        (is (some? s))
        (is (qs/started? s))
        (finally
          (qs/shutdown s))))))

(deftest test-stop-scheduler-shuts-down-running-scheduler
  (testing "stop-scheduler stops a running Quartz scheduler"
    (let [s (#'core/init-scheduler)]
      (is (qs/started? s) "precondition: scheduler is started")
      (#'core/stop-scheduler s)
      (is (qs/shutdown? s) "scheduler should be shut down"))))
