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
            [cheshire.core :as cheshire]))

(def local-connection (ref nil))

(defn connection
  ([]
   (deref local-connection))
  ([val]
   (dosync (ref-set local-connection val))))

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
      (log/error (format "[amqp/message-router] [%s] [%s] unroutable" routing-key (String. msg))))))

(defn connect
  [exchange-cfg queue-cfg handlers]
  (let [connection (rmq/connect {:uri (config/amqp-uri)})
        channel (lch/open connection)]
    (log/info (format "[amqp/connect] [%s]" (config/amqp-uri)))
    (declare-exchange channel exchange-cfg)
    (declare-queue channel exchange-cfg queue-cfg (keys handlers))
    (lc/blocking-subscribe channel (:name queue-cfg) (partial message-router handlers))
    connection))

