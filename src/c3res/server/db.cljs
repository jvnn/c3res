(ns c3res.server.db
  (:require [cljs.nodejs :as node]
            [cljs.core.async :as async :refer [<! chan put!]]
            [clojure.string :as s])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def sqlite (node/require "sqlite3"))
(def create-table-cmds
  ["CREATE TABLE IF NOT EXISTS shards (shard_id VARCHAR(64) PRIMARY KEY, timestamp INT, author VARCHAR(64));"
   "CREATE TABLE IF NOT EXISTS caps (shard_id VARCHAR(64) REFERENCES shards(shard_id), cap_shard_id VARCHAR(64) REFERENCES shards(shard_id)); "
   "CREATE TABLE IF NOT EXISTS labels (label_key TEXT, label_value TEXT, shard_id VARCHAR(64) REFERENCES shards(shard_id));"
   "CREATE TABLE IF NOT EXISTS relations (shard_id VARCHAR(64) REFERENCES shards(shard_id), other_id VARCHAR(64) REFERENCES shards(shard_id), relation_name TEXT;"])

(defn- create-tables [db]
  (let [c (chan)]
    (doseq [cmd create-table-cmds]
      (.exec db cmd #(put! c (nil? %))))
    c))

(defn open-db [path]
  (go
    (let [c (chan)
          Database (.-Database sqlite)
          db (Database. path (bit-or (.-OPEN_READWRITE sqlite) (.-OPEN_CREATE sqlite)) #(put! c (nil? %)))]
      (if (<! c)
        (if (<! (create-tables db))
          db
          false)
        false))))

(defn store-shard-metadata [db shard-id timestamp author labels]
  (let [shards-stmt (.prepare db "INSERT INTO shards VALUES (?, ?, ?)")
        labels-stmt (.prepare db "INSERT INTO labels VALUES (?, ?, ?)")]
    (.run shards-stmt shard-id timestamp author (fn [error] (when error (print "Error when storing metadata to shards table:" error))))
    (doseq [label-key (keys labels)]
      (.run labels-stmt label-key (labels label-key) shard-id (fn [error] (when error (print "Error when storing labels:" error)))))))

