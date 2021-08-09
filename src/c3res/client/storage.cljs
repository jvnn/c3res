(ns c3res.client.storage
  (:require [cljs.nodejs :as node]
            [cljs.core.async :as async :refer [<! put! chan]])
  (:require-macros [cljs.core.async.macros :refer [go]])) 

;TODO: separate implementations of this namespace for node and browser

(def fs (node/require "fs"))
(def os (node/require "os"))
(def path (node/require "path"))

(def default-opts {:master-key-path (.join path (.homedir os) ".c3res/master-key-input")
                   :shard-cache-path (.join path (.homedir os) ".c3res/cache")})

(defn- ensure-dir [path]
  (let [c (chan)]
    (.mkdir fs path (clj->js {:recursive true :mode 0750}) #(put! c (nil? %1)))
    c))

(defn file-accessible [path]
  (let [c (chan)]
    (.access fs path #(if %1 (put! c false) (put! c true)))
    c))

(defn join-opts [opts]
  (reduce #(if (default-opts %2) (assoc %1 %2 (opts %2)) %1) default-opts (keys opts)))

(defn store-master-key-input [custom-opts csexp]
  (let [opts (join-opts custom-opts)
        key-path (opts :master-key-path)
        c (chan)]
    (go
      (if (<! (ensure-dir (.dirname path key-path)))
        (.writeFile fs key-path csexp #(if %1 (put! c false) (put! c true)))
        (put! c false)))
    c))

(defn get-master-key-input [custom-opts]
  (let [opts (join-opts custom-opts)
        c (chan)]
    (.readFile fs (opts :master-key-path) #(if %1 (put! c false) (put! c %2)))
    c))

(defn- get-cache-filename [custom-opts id]
   (let [opts (join-opts custom-opts)
         cache-path (opts :shard-cache-path)
         id-start (subs id 0 2)]
     (.join path cache-path id-start id)))

(defn cache-shard [shard custom-opts]
  (let [c (chan)
        cache-file (get-cache-filename custom-opts (:id shard))]
    (go
      (if (<! (ensure-dir (.dirname path cache-file)))
        (.writeFile fs cache-file (:data shard) #(if %1 (put! c false) (put! c true)))
        (put! c false)))
    c))

(defn get-from-cache [id custom-opts]
  (let [path (get-cache-filename custom-opts id)
        c (chan)]
    (go
      (if (<! (file-accessible path))
        (.readFile fs path #(if %1 (put! c false) (put! c %2)))
        (put! c false)))
    c))

