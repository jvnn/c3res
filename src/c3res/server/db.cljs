(ns c3res.server.db
  (:require [cljs.nodejs :as node]
            [cljs.core.async :as async :refer [<! chan put!]]
            [clojure.string :as s])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def sqlite (node/require "sqlite3"))

(defn- create-tables [db]
  (let [c (chan)]
    (.exec db "CREATE TABLE IF NOT EXISTS foo (id TEXT PRIMARY KEY NOT NULL)" #(put! c (nil? %)))
    c))

(defn open-db [path]
  (go
    (let [c (chan)
          Database (.-Database sqlite)
          db (Database. path (bit-or (.-OPEN_READWRITE sqlite) (.-OPEN_CREATE sqlite)) #(put! c (nil? %)))]
      (if (<! c)
        (if (<! (create-tables db)) db false)
        false))))


