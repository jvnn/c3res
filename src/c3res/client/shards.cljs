(ns c3res.client.shards
  (:require [c3res.shared.csexp :as csexp]
            [c3res.client.sodiumhelper :as sod]
            [cljs.nodejs :as node]
            [cljs.core.async :as async :refer [chan close!]]))

(def sodium (sod/get-sodium))

(defn generate-keys []
  (let [keypair (.crypto_box_keypair sodium)]
    {:public (.-publicKey keypair) :private (.-privateKey keypair)}))

(defn csexp-to-map [csexp]
  ; by convention c3res is using key-value pairs in the root level s-expressions,
  ; so lets make life easier by converting them to clj maps with keywords
  (apply hash-map (map-indexed #(if (even? %1) (keyword %2) %2) csexp)))

(defn create-shard [plaintext tags contenttype author-keypair]
  (let [stream-key (.crypto_secretbox_keygen sodium)
        nonce (.randombytes_buf sodium (.-crypto_secretbox_NONCEBYTES sodium))
        contents (seq ["timestamp" (str (.now js/Date)) "type" contenttype "raw" plaintext "tags" (seq tags)])
        box (.crypto_secretbox_easy sodium (csexp/encode contents) nonce stream-key)
        author-cap (.crypto_box_seal sodium stream-key (:public author-keypair))
        shard (csexp/encode (seq ["authorcap" (seq ["bin" author-cap]) "nonce" (seq ["bin" nonce]) "data" (seq ["bin" box])]))
        shard-id (.crypto_generichash sodium (.-crypto_generichash_BYTES sodium) shard)]
    {:shard-id shard-id :shard shard}))

; currently just for my own shards: todo, add support for read caps
(defn read-shard [shard my-keypair]
  (let [shard-map (csexp-to-map (csexp/decode shard))
        cap (second (:authorcap shard-map))
        nonce (second (:nonce shard-map))
        data (second (:data shard-map))
        stream-key (.crypto_box_seal_open sodium cap (:public my-keypair) (:private my-keypair))
        plaintext (.crypto_secretbox_open_easy sodium data nonce stream-key)]
    (csexp-to-map (csexp/decode plaintext))))

