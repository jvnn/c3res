(ns c3res.shared.shards
  (:require [c3res.shared.csexp :as csexp]
            [c3res.client.sodiumhelper :as sod]
            [cljs.nodejs :as node]
            [cljs.core.async :as async :refer [chan close!]]))

(def sodium (sod/get-sodium))

; see rationale for two sets of keys in keystore.cljs
(defn generate-keys []
  (let [sign-keypair (.crypto_sign_keypair sodium)
        enc-pubkey (.crypto_sign_ed25519_pk_to_curve25519 sodium (.-publicKey sign-keypair))
        enc-privkey (.crypto_sign_ed25519_sk_to_curve25519 sodium (.-privateKey sign-keypair))]
    {:sign-public (.-publicKey sign-keypair) :sign-private (.-privateKey sign-keypair)
     :enc-public enc-pubkey :enc-private enc-privkey}))

(defn csexp-to-map [csexp]
  (reduce #(assoc %1 (keyword (first %2)) (second %2)) {} (rest csexp)))

; shard stucture:
;   (shard-id ("shard" ("author" ...) ("rootcap" ...) ("nonce" ...)
;                      ("encrypted" ("data" ("type" ...) ("raw" ...))))
;             ("signature" ...) ("caps" ...))
(defn create-shard [data contenttype root-keypair author-keypair]
  (let [stream-key (.crypto_secretbox_keygen sodium)
        author (.to_hex sodium (:sign-public author-keypair))
        nonce (.randombytes_buf sodium (.-crypto_secretbox_NONCEBYTES sodium))
        contents (seq ["data" (seq ["type" contenttype]) (seq ["raw" data])])
        box (.crypto_secretbox_easy sodium (csexp/encode contents) nonce stream-key)
        ; TODO: Add random bytes to author-cap's input to prevent recipients from knowing if this cap is for the stated "author" of this shard
        root-cap (.crypto_box_seal sodium stream-key (:enc-public root-keypair))
        shard (csexp/encode (seq ["shard" (seq ["author" author]) (seq ["rootcap" root-cap]) (seq ["nonce" nonce]) (seq ["encrypted" box])]))
        shard-id (.crypto_generichash sodium (.-crypto_generichash_BYTES sodium) shard)
        shard-id-str (.to_hex sodium shard-id)
        signature (.crypto_sign_detached sodium shard-id-str (:sign-private author-keypair))
        envelope (csexp/wrap shard shard-id-str)]
    {:id shard-id-str :data (csexp/append envelope (seq ["signature" signature]))}))

; the main shard only contains the data and data type - everything else is in the metadata shard
(defn create-with-metadata [contents contenttype labels root-keypair author-keypair]
  (let [keyvaluelabels (map seq (seq labels)) ; first seq converts a map to sequence of vectors
        metadata (seq ["metadata" (seq ["timestamp" (str (.now js/Date))]) (seq ["labels" (conj keyvaluelabels "map")])])]
    {:shard (create-shard contents contenttype root-keypair author-keypair)
     :metadata (create-shard metadata "c3res/metadata" root-keypair author-keypair)}))

; currently just for my own shards: todo, add support for read caps, multiple keypairs (due to multiple identities)
(defn read-shard [id full-shard my-keypair]
  (let [root-parts (csexp/decode-single-layer full-shard)
        stored-id (first root-parts)
        shard (second root-parts)
        signature (second (csexp/decode (nth root-parts 2)))
        calculated-id (.to_hex sodium (.crypto_generichash sodium (.-crypto_generichash_BYTES sodium) shard))]
    (if (not (and (= calculated-id id) (= stored-id id)))
      {:error (str "ID mismatch: expected " id ", calculated " calculated-id ", stored in envelope " stored-id)}
      (let [shard-map (csexp-to-map (csexp/decode shard))
            author-pubkey (.from_hex sodium (:author shard-map))]
        (if (not (.crypto_sign_verify_detached sodium signature id author-pubkey))
          {:error "Failed to validate shard author's signature"}
          (let [stream-key (.crypto_box_seal_open sodium (:rootcap shard-map) (:enc-public my-keypair) (:enc-private my-keypair))
                plaintext (.crypto_secretbox_open_easy sodium (:encrypted shard-map) (:nonce shard-map) stream-key)
                shard-as-map (csexp-to-map (csexp/decode plaintext))]
            (if (= (:type shard-as-map) "c3res/metadata")
              ; special handling for metadata: replace "raw" with pre-processed map
              (let [metadata (csexp-to-map (:raw shard-as-map))
                    shard-without-raw (dissoc shard-as-map :raw)]
                (assoc shard-without-raw :metadata (assoc metadata :labels (apply hash-map (flatten (rest (:labels metadata)))))))
              shard-as-map)))))))

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

