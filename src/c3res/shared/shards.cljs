(ns c3res.shared.shards
  (:require [c3res.shared.csexp :as csexp]
            [c3res.client.sodiumhelper :as sod]
            [cljs.nodejs :as node]
            [cljs.core.async :as async :refer [chan close!]]))

(def sodium (sod/get-sodium))

(defn generate-keys []
  (let [keypair (.crypto_box_keypair sodium)]
    {:public (.-publicKey keypair) :private (.-privateKey keypair)}))

(defn csexp-to-map [csexp]
  (reduce #(assoc %1 (keyword (first %2)) (second %2)) {} (rest csexp)))

; shard stucture: ("shard" ("authorcap" ...) ("nonce" ...) ("encrypted" ("data" ("type" ...) ("raw" ...))))
(defn create-shard [data contenttype author-keypair]
  (let [stream-key (.crypto_secretbox_keygen sodium)
        nonce (.randombytes_buf sodium (.-crypto_secretbox_NONCEBYTES sodium))
        contents (seq ["data" (seq ["type" contenttype]) (seq ["raw" data])])
        box (.crypto_secretbox_easy sodium (csexp/encode contents) nonce stream-key)
        author-cap (.crypto_box_seal sodium stream-key (:public author-keypair))
        shard (csexp/encode (seq ["shard" (seq ["authorcap" author-cap]) (seq ["nonce" nonce]) (seq ["encrypted" box])]))
        shard-id (.crypto_generichash sodium (.-crypto_generichash_BYTES sodium) shard)]
    {:id (.to_hex sodium shard-id) :data shard}))

; the main shard only contains the data and data type - everything else is in the metadata shard
(defn create-with-metadata [contents contenttype labels author-keypair]
  (let [keyvaluelabels (map seq (seq labels)) ; first seq converts a map to sequence of vectors
        metadata (seq ["metadata" (seq ["timestamp" (str (.now js/Date))]) (seq ["labels" (conj keyvaluelabels "map")])])]
    {:shard (create-shard contents contenttype author-keypair)
     :metadata (create-shard metadata "c3res/metadata" author-keypair)}))

; currently just for my own shards: todo, add support for read caps
(defn read-shard [shard my-keypair]
  (let [shard-map (csexp-to-map (csexp/decode shard))
        stream-key (.crypto_box_seal_open sodium (:authorcap shard-map) (:public my-keypair) (:private my-keypair))
        plaintext (.crypto_secretbox_open_easy sodium (:encrypted shard-map) (:nonce shard-map) stream-key)
        shard-as-map (csexp-to-map (csexp/decode plaintext))]
    (if (= (:type shard-as-map) "c3res/metadata")
      ; special handling for metadata: replace "raw" with pre-processed map
      (let [metadata (csexp-to-map (:raw shard-as-map))
            shard-without-raw (dissoc shard-as-map :raw)]
        (assoc shard-without-raw :metadata (assoc metadata :labels (apply hash-map (flatten (rest (:labels metadata)))))))
      shard-as-map)))

(defn pretty-print [shard]
  (print "-----")
  (if (= (:type shard) "c3res/metadata")
    (do
      (print "type:\t\tc3res/metadata")
      (doseq [part [[:timestamp "\t"] [:labels "\t\t"]]]
        (print (str (name (first part)) ":" (second part) ((:metadata shard) (first part))))))
    (doseq [part [[:type "\t"] [:raw "\n"]]]
      (print (str (name (first part)) ":" (second part) (shard (first part)))))
    )
  (print "-----"))

