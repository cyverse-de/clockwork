(ns clockwork.config
  (:use [slingshot.slingshot :only [throw+]])
  (:require [clojure-commons.config :as cc]
            [clojure-commons.error-codes :as ce]))

(def ^:private props
  "A ref for storing the configuration properties."
  (ref nil))

(def ^:private config-valid
  "A ref for storing a configuration validity flag."
  (ref true))

(def ^:private configs
  "A ref for storing the symbols used to get configuration settings."
  (ref []))

(cc/defprop-optstr irods-host
  "The host name or IP address to use when connecting to iRODS."
  [props config-valid configs]
  "clockwork.irods-host" "irods")

(cc/defprop-optstr irods-port
  "The port number to use when connecting to iRODS."
  [props config-valid configs]
  "clockwork.irods-port" "1247")

(cc/defprop-optstr irods-user
  "The username to use when authenticating to iRODS."
  [props config-valid configs]
  "clockwork.irods-user" "rods")

(cc/defprop-optstr irods-password
  "The password t use when authenticating to iRODS."
  [props config-valid configs]
  "clockwork.irods-password" "notprod")

(cc/defprop-optstr irods-home
  "The base path to the directory containing the home directories in iRODS."
  [props config-valid configs]
  "clockwork.irods-home" "/iplant/home")

(cc/defprop-optstr irods-zone
  "The name of the iRODS zone."
  [props config-valid configs]
  "clockwork.irods-zone" "iplant")

(cc/defprop-optstr irods-resource
  "The name of the default resource to use in iRODS."
  [props config-valid configs]
  "clockwork.irods-resource" "")

(cc/defprop-optstr notification-cleanup-start
  "The start time for the notification cleanup job."
  [props config-valid configs]
  "clockwork.notifications.cleanup-start" "1:45:00")

(cc/defprop-optint notification-cleanup-age
  "The minimum age of a notification in days before it's eligible for cleanup."
  [props config-valid configs]
  "clockwork.notifications.cleanup-age" 90)

(cc/defprop-optboolean notification-cleanup-enabled
  "Indicates whether notification cleanup tasks are enabled."
  [props config-valid configs]
  "clockwork.notifications.cleanup-enable" true)

(cc/defprop-optstr infosquito-job-basename
  "Basename of infosquito indexing task."
  [props config-valid configs]
  "clockwork.jobs.infosquito.basename" "indexing.1")

(cc/defprop-optboolean infosquito-indexing-enabled
  "Indicates whether infosquito indexing tasks are enabled."
  [props config-valid configs]
  "clockwork.jobs.infosquito.indexing-enabled" true)

(cc/defprop-optstr notification-db-driver-class
  "The name of the JDBC driver to use."
  [props config-valid configs]
  "clockwork.notifications.db.driver" "org.postgresql.Driver")

(cc/defprop-optstr notification-db-subprotocol
  "The subprotocol to use when connecting to the database (e.g. postgresql)."
  [props config-valid configs]
  "clockwork.notifications.db.subprotocol" "postgresql")

(cc/defprop-optstr notification-db-host
  "The host name or IP address to use when connecting to the database."
  [props config-valid configs]
  "clockwork.notifications.db.host" "dedb")

(cc/defprop-optstr notification-db-port
  "The port number to use when connecting to the database."
  [props config-valid configs]
  "clockwork.notifications.db.port" "5432")

(cc/defprop-optstr notification-db-name
  "The name of the database to connect to."
  [props config-valid configs]
  "clockwork.notifications.db.name" "notifications")

(cc/defprop-optstr notification-db-user
  "The username to use when authenticating to the database."
  [props config-valid configs]
  "clockwork.notifications.db.user" "de")

(cc/defprop-optstr notification-db-password
  "The password to use when authenticating to the database."
  [props config-valid configs]
  "clockwork.notifications.db.password" "notprod")

(cc/defprop-optstr amqp-uri
  "The URI to use to establish AMQP connections."
  [props config-valid configs]
  "clockwork.amqp.uri" "amqp://guest:guest@rabbit:5672/")

(cc/defprop-optstr exchange-name
  "The name of AMQP exchange to connect to."
  [props config-valid configs]
  "clockwork.amqp.exchange.name" "de")

(cc/defprop-optboolean exchange-durable?
  "Whether or not the AMQP exchange is durable."
  [props config-valid configs]
  "clockwork.amqp.exchange.durable" true)

(cc/defprop-optboolean exchange-auto-delete?
  "Whether or not the AMQP exchange is auto-deleted."
  [props config-valid configs]
  "clockwork.amqp.exchange.auto-delete" false)

(cc/defprop-optstr queue-name
  "The name of the AMQP queue that is used for clockwork."
  [props config-valid configs]
  "clockwork.amqp.queue.name" "events.clockwork.queue")

(cc/defprop-optboolean queue-durable?
  "Whether or not the AMQP queue is durable."
  [props config-valid configs]
  "clockwork.amqp.queue.durable" true)

(cc/defprop-optboolean queue-auto-delete?
  "Whether or not to delete the AMQP queue."
  [props config-valid configs]
  "clockwork.amqp.queue.auto-delete" false)

(defn- validate-config
  "Validates the configuration settings after they've been loaded."
  []
  (when-not (cc/validate-config configs config-valid)
    (throw+ {:error_code ce/ERR_CONFIG_INVALID})))

(defn load-config-from-file
  "Loads the configuration settings from a file."
  [cfg-path]
  (cc/load-config-from-file cfg-path props)
  (cc/log-config props)
  (validate-config))
