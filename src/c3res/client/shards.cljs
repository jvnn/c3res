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
  ; expected stucture: ("shard" ("raw" "some contents") ("timestamp" "1234321323") ("labels" ("map" (...)) ... )
  (->> (rest csexp)
       (map
         #(if (and (= (first %) "labels") (= (first (second %)) "map"))
            (seq ["labels" (apply hash-map (flatten (rest (second %))))])
            %))
       (reduce #(assoc %1 (keyword (first %2)) (second %2)) {})))

(defn create-shard [plaintext labels contenttype author-keypair]
  (let [stream-key (.crypto_secretbox_keygen sodium)
        nonce (.randombytes_buf sodium (.-crypto_secretbox_NONCEBYTES sodium))
        keyvaluelabels (map seq (seq labels)) ; first seq converts a map to sequence of vectors
        contents (seq ["contents" (seq ["timestamp" (str (.now js/Date))]) (seq ["type" contenttype]) (seq ["raw" plaintext]) (seq ["labels" (conj keyvaluelabels "map")])])
        box (.crypto_secretbox_easy sodium (csexp/encode contents) nonce stream-key)
        author-cap (.crypto_box_seal sodium stream-key (:public author-keypair))
        shard (csexp/encode (seq ["shard" (seq ["authorcap" author-cap]) (seq ["nonce" nonce]) (seq ["data" box])]))
        shard-id (.crypto_generichash sodium (.-crypto_generichash_BYTES sodium) shard)]
    {:id (.to_hex sodium shard-id) :data shard}))

; currently just for my own shards: todo, add support for read caps
(defn read-shard [shard my-keypair]
  (let [shard-map (csexp-to-map (csexp/decode shard))
        cap (:authorcap shard-map)
        nonce (:nonce shard-map)
        data (:data shard-map)
        stream-key (.crypto_box_seal_open sodium cap (:public my-keypair) (:private my-keypair))
        plaintext (.crypto_secretbox_open_easy sodium data nonce stream-key)]
    (csexp-to-map (csexp/decode plaintext))))

(defn pretty-print [shard]
  (print "-----")
  (doseq [part [[:timestamp "\t"] [:type "\t\t"] [:labels "\t\t"] [:raw "\n"]]]
    (print (str (name (first part)) ":" (second part) (shard (first part)))))
  (print "-----"))

