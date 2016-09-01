(ns clockwork.core-test
  (:use clojure.test
        clockwork.core)
  (:require [clockwork.config :as config]))

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
    (is (= (config/notification-cleanup-start) "1:45:00"))
    (is (= (config/notification-cleanup-age) 90))
    (is (true? (config/notification-cleanup-enabled)))
    (is (= (config/notification-db-driver-class) "org.postgresql.Driver"))
    (is (= (config/notification-db-subprotocol) "postgresql"))
    (is (= (config/notification-db-host) "dedb"))
    (is (= (config/notification-db-port) "5432"))
    (is (= (config/notification-db-name) "notifications"))
    (is (= (config/notification-db-user) "de"))
    (is (= (config/notification-db-password) "notprod"))))
