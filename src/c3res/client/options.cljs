(ns c3res.client.options
  (:require [cljs.nodejs :as node]))

(def os (node/require "os"))
(def path (node/require "path"))

(def default-opts {:master-key-path (.join path (.homedir os) ".c3res/master-key-input")
                   :shard-cache-path (.join path (.homedir os) ".c3res/cache")})

(defn join-opts [opts]
  (reduce #(if (default-opts %2) (assoc %1 %2 (opts %2)) %1) default-opts (keys opts)))

(defn get-master-key-input-path [custom-opts]
  (:master-key-path (join-opts custom-opts)))

(defn get-shard-cache-path [custom-opts]
  (:shard-cache-path (join-opts custom-opts)))
