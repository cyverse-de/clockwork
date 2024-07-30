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

(cc/defprop-optstr infosquito-job-basename
  "Basename of infosquito indexing task."
  [props config-valid configs]
  "clockwork.jobs.infosquito.basename" "indexing.1")

(cc/defprop-optint infosquito-job-daynum
  "Numeric day of week [1-7] to run the weekly indexing task."
  [props config-valid configs]
  "clockwork.jobs.infosquito.daynum" 1)

(cc/defprop-optboolean infosquito-indexing-enabled
  "Indicates whether infosquito indexing tasks are enabled."
  [props config-valid configs]
  "clockwork.jobs.infosquito.indexing-enabled" true)

(cc/defprop-optstr data-usage-api-job-basename
  "Basename of data-usage-api indexing task."
  [props config-valid configs]
  "clockwork.jobs.data-usage-api.basename" "data-usage.1")

(cc/defprop-optint data-usage-api-interval
  "Numeric number of hours for the interval between data-usage-api indexing"
  [props config-valid configs]
  "clockwork.jobs.data-usage-api.interval" 3)

(cc/defprop-optboolean data-usage-api-indexing-enabled
  "Indicates whether data-usage-api indexing tasks are enabled."
  [props config-valid configs]
  "clockwork.jobs.data-usage-api.indexing-enabled" true)

(cc/defprop-optstr amqp-uri
  "The URI to use to establish AMQP connections."
  [props config-valid configs]
  "clockwork.amqp.uri" "amqp://guest:guest@rabbit:5672/")

(cc/defprop-optstr exchange-name
  "The name of AMQP exchange to connect to."
  [props config-valid configs]
  "clockwork.amqp.exchange.name" "de")

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
