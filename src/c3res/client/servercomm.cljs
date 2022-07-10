(ns c3res.client.servercomm
  (:require [c3res.client.http :as http]
            [c3res.shared.csexp :as csexp]
            [cljs.core.async :as async :refer [<! >! put! chan close!]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(defn upload [server-config shards]
  (go
    (let [shard (:shard shards)
          metadata (:metadata shards)
          sharddata (csexp/append-raw (csexp/encode '("shard")) (:data shard))
          metadatadata (csexp/append-raw (csexp/encode '("metadata")) (:data metadata))
          envelope (-> (csexp/encode '("c3res-envelope")) (csexp/append-raw sharddata) (csexp/append-raw metadatadata))
          resp (<! (http/do-post (:server server-config) (:port server-config) "/shard" (.from js/Buffer (.-buffer envelope)) {}))]
      ; TODO: Better error handling, retries, buffering, ...
      (if (not= (:status resp) 200)
        (print "Failed to upload shard " (:id shard) " with metadata " (:id metadata) ": status " (:status resp) ", error: " (:data resp))
        (print "Successfully uploaded shard " (:id shard) " with metadata " (:id metadata))))))

(defn fetch [server-config id]
  (go
    (let [resp (<! (http/do-get (:server server-config) (:port server-config) (str "/shard/" id)))]
      (if (not= (:status resp) 200)
        (print "Failed to fetch shard " id "- status:" (:status resp) "error:" (:data resp))
        (:data resp)))))
