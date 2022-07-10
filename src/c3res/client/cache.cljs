(ns c3res.client.cache
  (:require [c3res.shared.shards :as shards]
            [c3res.shared.storage :as storage]
            [c3res.client.servercomm :as servercomm]
            [clojure.string :as s]
            [cljs.core.async :as async :refer [<! >!]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(defn- cache-and-upload [shard-and-metadata cache-path server-config]
  (go
    (let [shard (:shard shard-and-metadata)
          metadata (:metadata shard-and-metadata)
          results [[(:id shard) (<! (storage/store-shard cache-path shard))]
                   [(:id metadata) (<! (storage/store-shard cache-path metadata))]]]
      ; try to upload regardless of how caching went
      (<! (servercomm/upload server-config shard-and-metadata))
      (reduce #(if-not (second %2) (conj %1 (first %2)) %1) '() results))))

(defn new-shard [cache-path content content-type labels my-keys server-config cap-keys]
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
      (let [shard-and-metadata (shards/create-with-metadata content content-type labels my-keys my-keys (:pubkey server-config) cap-keys)
            failed-ids (<! (cache-and-upload shard-and-metadata cache-path server-config))]
        (if (seq failed-ids)
          {:error (str "Failed to cache following ids: " (s/join " " failed-ids))}
          {:shard-id (:id (:shard shard-and-metadata)) :metadata-id (:id (:metadata shard-and-metadata))})))))

; TODO: cache the fetched shard
(defn fetch [cache-path id my-keys server-config]
  (go
    (when-let [shard (or (<! (storage/get-shard cache-path id)) (<! (servercomm/fetch server-config id)))]
      (let [id-and-author (shards/validate-shard shard)]
        (cond
          (:error id-and-author) (print "Fetched shard did not pass validation:" (:error id-and-author))
          (not= id (:id id-and-author)) (print "ID mismatch in cache: file stored as" id " but validation returned " (:id id-and-author))
          :else
          (let [shard-map (shards/read-shard shard my-keys)]
            (if (:error shard-map)
              (print "Failed to retrieve a shard: " (:error shard-map))
              shard-map)))))))

