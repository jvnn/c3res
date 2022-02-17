(ns c3res.client.cache
  (:require [c3res.shared.shards :as shards]
            [c3res.shared.storage :as storage]
            [clojure.string :as s]
            [cljs.core.async :as async :refer [<! >!]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(defn- cache-and-upload [shards cache-path upstream-chan]
  (go-loop [shard (first shards) remaining (rest shards) failed-ids []]
    (if (nil? shard)
      (do
        ; try to upload regardless of how caching went
        (>! upstream-chan shards)
        failed-ids)
      (if (<! (storage/store-shard cache-path shard))
        (recur (first remaining) (rest remaining) failed-ids)
        (recur (first remaining) (rest remaining) (conj failed-ids (:id shard)))))))

(defn new-shard [cache-path content content-type labels upstream-chan my-keys server-pubkey cap-keys]
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
      (let [shard-and-metadata (shards/create-with-metadata content content-type labels my-keys my-keys server-pubkey cap-keys)
            failed-ids (<! (cache-and-upload [(:shard shard-and-metadata) (:metadata shard-and-metadata)] cache-path upstream-chan))]
        (if (seq failed-ids)
          {:error (str "Failed to cache following ids: " (s/join " " failed-ids))}
          {:shard-id (:id (:shard shard-and-metadata)) :metadata-id (:id (:metadata shard-and-metadata))})))))

; XXX: improve error handling: throw or return :error maps
(defn fetch [cache-path id my-keys]
  (go
    ; TODO: replace nil here with a request to server 
    (when-let [shard (or (<! (storage/get-shard cache-path id)) nil)]
      (let [id-and-author (shards/validate-shard shard)]
        (cond
          (:error id-and-author) (print "Cached shard did not pass validation:" (:error id-and-author))
          (not= id (:id id-and-author)) (print "ID mismatch in cache: file stored as" id " but validation returned " (:id id-and-author))
          :else
          (let [shard-map (shards/read-shard shard my-keys)]
            (if (:error shard-map)
              (print "Failed to retrieve a shard: " (:error shard-map))
              shard-map)))))))

