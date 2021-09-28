(ns c3res.shared.storage
  (:require [cljs.nodejs :as node]
            [cljs.core.async :as async :refer [<! put! chan]])
  (:require-macros [cljs.core.async.macros :refer [go]])) 

;TODO: separate implementations of this namespace for node and browser

(def fs (node/require "fs"))
(def path (node/require "path"))

(defn- ensure-dir [path]
  (let [c (chan)]
    (.mkdir fs path (clj->js {:recursive true :mode 0750}) #(put! c (nil? %1)))
    c))

(defn file-accessible [path]
  (let [c (chan)]
    (.access fs path #(if %1 (put! c false) (put! c true)))
    c))

(defn store-file [filename data]
  (let [c (chan)]
    (go
      (if (<! (ensure-dir (.dirname path filename)))
        (.writeFile fs filename data #(if %1 (put! c false) (put! c true)))
        (put! c false)))
    c))

(defn get-file [filename]
  (let [c (chan)]
    (.readFile fs filename #(if %1 (put! c false) (put! c %2)))
    c))

(defn- get-cache-filename [cache-path id]
   (let [id-start (subs id 0 2)]
     (.join path cache-path id-start id)))

(defn cache-shard [cache-path shard]
  (let [cache-file (get-cache-filename cache-path (:id shard))]
    (store-file cache-file (:data shard))))

(defn get-from-cache [cache-path id]
  (let [filepath (get-cache-filename cache-path id)
        c (chan)]
    (get-file filepath)))

