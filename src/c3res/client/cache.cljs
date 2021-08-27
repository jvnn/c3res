(ns c3res.client.cache
  (:require [c3res.client.shards :as shards]
            [c3res.client.storage :as storage]
            [clojure.string :as s]
            [cljs.core.async :as async :refer [<! >!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

; TODO: currently only supporting own keys, no targets
(defn new-shard [cache-path content labels content-type upstream-chan my-keys]
  (go
    (cond
      (not (map? labels))
      {:error "Invalid labels provided: expecting a string-string map"}
      (not (and (every? string? (keys labels)) (every? string? (vals labels))))
      {:error "Invalid labels provided: keys and values should be strings"}
      (or (not (string? content-type)) (s/blank? content-type))
      {:error "Invalid content type: expecting a string"}
      (or (s/blank? content) (empty? content))
      {:error "Invalid content: expecting a non-empty string or byte array"}
      :else
      (let [shard (shards/create-shard content labels content-type my-keys)]
        (if-not (<! (storage/cache-shard cache-path shard))
          {:error "Failed to cache shard to storage"}
          (do
            (>! upstream-chan (:id shard))
            (:id shard)))))))

(defn fetch [cache-path id my-keys]
  (go
    ; TODO: replace nil here with a request to server 
    (when-let [shard (or (<! (storage/get-from-cache cache-path id)) nil)]
      (shards/read-shard shard my-keys))))

