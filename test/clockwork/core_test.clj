(ns clockwork.core-test
  (:use clojure.test)
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
    (is (= (config/infosquito-job-basename) "indexing.1"))
    (is (= (config/infosquito-job-daynum) 1))
    (is (= (config/amqp-uri) "amqp://guest:guest@rabbit:5672/"))
    (is (= (config/exchange-name) "de"))))
