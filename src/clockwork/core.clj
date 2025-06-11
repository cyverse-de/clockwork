(ns clockwork.core
  (:gen-class)
  (:use [slingshot.slingshot :only [try+]])
  (:require [clojure.tools.logging :as log]
            [clojure.string :as string]
            [clockwork.amqp :as amqp]
            [clockwork.config :as config]
            [clojurewerkz.quartzite.jobs :as qj]
            [clojurewerkz.quartzite.scheduler :as qs]
            [clojurewerkz.quartzite.triggers :as qt]
            [clojurewerkz.quartzite.schedule.cron :as qsc]
            [clojurewerkz.quartzite.schedule.calendar-interval :as qsci]
            [common-cli.core :as ccli]
            [me.raynes.fs :as fs]
            [service-logging.thread-context :as tc]))

(defn- split-timestamp
  "Splits a timestamp into its components.  The timestamp should be in the format, HH:MM.  If
   seconds are included in the timestamp, they will be ignored."
  [timestamp error-message]
  (try+
   (->> (string/split timestamp #":")
        (map #(Long/parseLong %))
        (take 2))
   (catch NumberFormatException e
     (log/error error-message timestamp)
     (System/exit 1))))

(defn- qualified-name
  "Creates a qualified name for a prefix and a given basename."
  [prefix base]
  (str prefix \. base))

(def ^:private job-name (partial qualified-name "jobs"))
(def ^:private trigger-name (partial qualified-name "triggers"))

(qj/defjob infosquito-indexing
  [_]
  (amqp/publish-msg "index.all" "Sent by clockwork"))

(defn- schedule-infosquito-indexing
  ""
  [s]
    (let [basename (config/infosquito-job-basename)
           job     (qj/build
                    (qj/of-type infosquito-indexing)
                    (qj/with-identity (qj/key (job-name basename))))
           trigger (qt/build
                    (qt/with-identity (qt/key (trigger-name basename)))
                    (qt/with-schedule (qsc/schedule
                                        (qsc/weekly-on-day-and-hour-and-minute (config/infosquito-job-daynum) 23 0)
                                        (qsc/ignore-misfires s))))]
       (qs/schedule s job trigger)
       (log/debug (qs/get-trigger s (trigger-name basename)))))

(qj/defjob data-usage-api-updates
  [_]
  (amqp/publish-msg "index.usage.data" "Sent by clockwork"))

(defn- schedule-data-usage-api
  ""
  [s]
  (let [basename (config/data-usage-api-job-basename)
        job      (qj/build
                   (qj/of-type data-usage-api-updates)
                   (qj/with-identity (qj/key (job-name basename))))
        trigger  (qt/build
                   (qt/with-identity (qt/key (trigger-name basename)))
                   (qt/with-schedule (qsci/schedule
                                       (qsci/with-interval-in-hours s (config/data-usage-api-interval))
                                       (qsci/ignore-misfires s))))]
    (qs/schedule s job trigger)
    (log/debug (qs/get-trigger s (trigger-name basename)))))

(defn- init-scheduler
  "Initializes the scheduler."
  []
  (let [s (-> (qs/initialize) qs/start)]
    (when (config/infosquito-indexing-enabled)
      (schedule-infosquito-indexing s))
    (when (config/data-usage-api-indexing-enabled)
      (schedule-data-usage-api s))))

(def svc-info
  {:desc "Scheduled jobs for the iPlant Discovery Environment"
   :app-name "clockwork"
   :group-id "org.cyverse"
   :art-id "clockwork"
   :service "clockwork"})

(defn cli-options
  []
  [["-c" "--config PATH" "Path to the config file"
    :default "/etc/iplant/de/clockwork.properties"]
   ["-v" "--version" "Print out the version number."]
   ["-h" "--help"]])

(defn -main
  [& args]
  (tc/with-logging-context svc-info
    (let [{:keys [options arguments errors summary]} (ccli/handle-args svc-info args cli-options)]
      (when-not (fs/exists? (:config options))
        (ccli/exit 1 (str "The config file does not exist.")))
      (when-not (fs/readable? (:config options))
        (ccli/exit 1 "The config file is not readable."))
      (log/info "clockwork startup")
      (config/load-config-from-file (:config options))
      (init-scheduler))))
