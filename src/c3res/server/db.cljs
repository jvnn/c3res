(ns c3res.server.db
  (:require [cljs.nodejs :as node]
            [cljs.core.async :as async :refer [<! chan put! close!]]
            [clojure.string :as s])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def sqlite (node/require "sqlite3"))
(def CURRENT_DB_VERSION 1)

(def create-table-cmds
  ["CREATE TABLE IF NOT EXISTS shards (shard_id VARCHAR(64) PRIMARY KEY, timestamp INT, author VARCHAR(64));"
   "CREATE TABLE IF NOT EXISTS caps (shard_id VARCHAR(64) REFERENCES shards(shard_id), cap_shard_id VARCHAR(64) REFERENCES shards(shard_id)); "
   "CREATE TABLE IF NOT EXISTS labels (label_key TEXT, label_value TEXT, shard_id VARCHAR(64) REFERENCES shards(shard_id), timestamp INT);"
   "CREATE TABLE IF NOT EXISTS relations (shard_id VARCHAR(64) REFERENCES shards(shard_id), other_id VARCHAR(64) REFERENCES shards(shard_id), relation_name TEXT;"
   (str "PRAGMA user_version = " CURRENT_DB_VERSION ";")])


(defn- get-version [db]
  (let [c (chan)]
    (.get db "PRAGMA user_version" #(if %1 (put! c false) (put! c ((js->clj %2) "user_version"))))
    c))

(defn- create-tables [db]
  (let [c (chan)]
    (doseq [cmd create-table-cmds]
      (.exec db cmd #(put! c (nil? %))))
    c))

(defn- migrate-0 [db]
  (let [c (chan)]
    (.exec db (str
               "ALTER TABLE labels ADD COLUMN timestamp INT;"
               "UPDATE labels SET timestamp = shard.timestamp FROM (SELECT timestamp, shard_id FROM shards) AS shard WHERE labels.shard_id = shard.shard_id;"
               "PRAGMA user_version = 1;")
          #(do (when % (print "Failed to migrate database from version 0:" %)) (close! c)))
    c))

; TODO: a unit test with different DB versions (one per version with some dummy data)
; checking that in the end all of those have migrated to the latest schema with meaningful data
(defn- migrate-db [version db]
  (go
    (cond
      (= version 0) (<! (migrate-0 db))
      ; add further migratio code here, make sure all migration steps are executed when difference is multiple versions
      )
    db))

(defn open-db [path]
  (go
    (let [c (chan)
          Database (.-Database sqlite)
          db (Database. path (bit-or (.-OPEN_READWRITE sqlite) (.-OPEN_CREATE sqlite)) #(put! c (nil? %)))]
      (if (<! c)
        (if (<! (create-tables db))
          (migrate-db (<! (get-version db)) db)
          false)
        false))))

(defn store-shard-metadata [db shard-id timestamp author labels]
  (let [shards-stmt (.prepare db "INSERT INTO shards VALUES (?, ?, ?)")
        labels-stmt (.prepare db "INSERT INTO labels VALUES (?, ?, ?, ?)")]
    (.run shards-stmt shard-id timestamp author (fn [error] (when error (print "Error when storing metadata to shards table:" error))))
    (doseq [label-key (keys labels)]
      (.run labels-stmt label-key (labels label-key) shard-id timestamp (fn [error] (when error (print "Error when storing labels:" error)))))))

(defn query-labels [db label value]
  (let [c (chan)
        labels-stmt (.prepare db "SELECT shard_id FROM labels WHERE \"label_key\" = ? AND \"label_value\" = ?")]
    (.all labels-stmt label value
          (fn [error rows]
            (if error
              (do (print error) (close! c))
              (put! c (mapv #(% "shard_id") (js->clj rows))))))
    c))

