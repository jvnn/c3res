(ns c3res.client.storage
  (:require [cljs.nodejs :as node]
            [cljs.core.async :as async :refer [close! put! chan]])
  (:require-macros [cljs.core.async.macros :refer [go]])) 

;TODO: separate implementations of this namespace for node and browser

(def fs (node/require "fs"))
(def os (node/require "os"))
(def path (node/require "path"))

(def default-opts {:master-key-path (.join path (.homedir os) ".c3res/master-key-input")
                   :shard-cache-path (.join path (.homedir os) ".c3res/cache")})

(defn join-opts [opts]
  (reduce #(if (default-opts %2) (assoc %1 %2 (opts %2)) %1) default-opts (keys opts)))

(defn store-master-key-input [custom-opts csexp]
  (let [opts (join-opts custom-opts)
        c (chan)]
    (.writeFile fs (opts :master-key-path) csexp #(if %1 (put! c false) (put! c true)))
    c))

(defn get-master-key-input [custom-opts]
  (let [opts (join-opts custom-opts)
        c (chan)]
    (.readFile fs (opts :master-key-path) #(if %1 (put! c false) (put! c %2)))
    c))

(defn cache-shard [shard custom-opts]
  (print (str "would cache shard with id " (:shard-id shard)))
  true)

