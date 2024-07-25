(ns clockwork.amqp
  (:use [slingshot.slingshot :only [try+]])
  (:require [clojure.tools.logging :as log]
            [clockwork.config :as config]
            [langohr.core :as rmq]
            [langohr.channel :as lch]
            [langohr.queue :as lq]
            [langohr.consumers :as lc]
            [langohr.exchange :as le]
            [langohr.basic :as lb]
            [service-logging.thread-context :as tc]
            [cheshire.core :as cheshire]))

(defn- declare-queue
  [channel {exchange-name :name} queue-cfg topics]
  (lq/declare channel (:name queue-cfg) (assoc queue-cfg :exclusive false))
  (doseq [key topics]
    (lq/bind channel (:name queue-cfg) exchange-name {:routing-key key})))

(defn- declare-exchange
  [channel {exchange-name :name :as exchange-cfg}]
  (le/topic channel exchange-name exchange-cfg))

(defn- message-router
  [handlers channel {:keys [delivery-tag routing-key] :as metadata} msg]
  (let [handler (get handlers routing-key)]
    (if-not (nil? handler)
      (handler channel metadata msg)
      (log/error (format "[amqp/message-router] [%s] [%s] unroutable" routing-key (str msg))))))

(defn connect
  [exchange-cfg queue-cfg handlers]
  (let [connection (rmq/connect {:uri (config/amqp-uri)})
        channel (lch/open connection)]
    (log/info (format "[amqp/connect] [%s]" (config/amqp-uri)))
    (declare-exchange channel exchange-cfg)
    (declare-queue channel exchange-cfg queue-cfg (keys handlers))
    (lc/blocking-subscribe channel (:name queue-cfg) (partial message-router handlers))
    connection))

(defn publish-msg
  [routing-key msg]
  (try+
    (let [timeNow (new java.util.Date)
          connection (rmq/connect {:uri (config/amqp-uri)})
          channel (lch/open connection)]
      (tc/with-logging-context
        {:amqp-routing-key routing-key
         :amqp-message msg}
        (log/info (format "Publishing AMQP message. routing-key=%s" routing-key)))
      (lb/publish channel
                  (config/exchange-name)
                  routing-key
                  (cheshire/encode {:message      msg
                                    :timestamp_ms (.getTime timeNow)})
                  {:content-type "application/json"
                   :timestamp    timeNow})

      (lch/close channel)
      (rmq/close connection))
    (catch Object _
      (log/error (:throwable &throw-context) "Failed to publish message" (cheshire/encode msg)))))
