(ns clockwork.events
  (:require [clojure.tools.logging :as log]
            [clockwork.config :as config]
            [clockwork.amqp :as amqp]
            [langohr.basic :as lb])
  (:import [org.cyverse.events.ping PingMessages$Pong]
           [com.google.protobuf.util JsonFormat]))

(defn exchange-config
  []
  {:name        (config/exchange-name)
   :type        (config/exchange-type)
   :durable     (config/exchange-durable?)
   :auto-delete (config/exchange-auto-delete?)})

(defn queue-config
  []
  {:name        (config/queue-name)
   :durable     (config/queue-durable?)
   :auto-delete (config/queue-auto-delete?)})

(defn ping-handler
  [channel {:keys [delivery-tag routing-key]} msg]
  (lb/ack channel delivery-tag)
  (log/info (format "[events/ping-handler] [%s] [%s]" routing-key (String. msg)))
  (lb/publish channel (config/exchange-name) "events.clockwork.pong"
    (.print (JsonFormat/printer)
      (.. (PingMessages$Pong/newBuilder)
        (setPongFrom "clockwork")
        (build)))))
