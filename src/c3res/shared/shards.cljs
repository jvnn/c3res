(ns c3res.shared.shards
  (:require [c3res.shared.csexp :as csexp]
            [c3res.shared.sodiumhelper :as sod]
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

(defn create-cap [stream-key target-pubkey]
  (let [keylen (.-length stream-key)
        random (.randombytes_buf sodium keylen)
        newbuf (js/Uint8Array. (* keylen 2))]
    (.set newbuf stream-key)
    (.set newbuf random keylen)
    (.crypto_box_seal sodium newbuf target-pubkey)))

; shard stucture:
;   (shard-id ("shard" ("author" ...) ("rootcap" ...) ("nonce" ...)
;                      ("encrypted" ("data" ("type" ...) ("raw" ...))))
;             ("signature" ...) ("caps" ...))
(defn create-shard [data contenttype root-keypair author-keypair cap-keys]
  (let [stream-key (.crypto_secretbox_keygen sodium)
        author (.to_hex sodium (:sign-public author-keypair))
        nonce (.randombytes_buf sodium (.-crypto_secretbox_NONCEBYTES sodium))
        contents (seq ["data" (seq ["type" contenttype]) (seq ["raw" data])])
        box (.crypto_secretbox_easy sodium (csexp/encode contents) nonce stream-key)
        root-cap (create-cap stream-key (:enc-public root-keypair))
        shard (csexp/encode (seq ["shard" (seq ["author" author]) (seq ["rootcap" root-cap]) (seq ["nonce" nonce]) (seq ["encrypted" box])]))
        shard-id (.crypto_generichash sodium (.-crypto_generichash_BYTES sodium) shard)
        shard-id-str (.to_hex sodium shard-id)
        signature (.crypto_sign_detached sodium shard-id-str (:sign-private author-keypair))
        caps (for [pubkey cap-keys] (create-cap stream-key pubkey))
        envelope (-> shard
                     (csexp/wrap shard-id-str)
                     (csexp/append (seq ["signature" signature])))]
    {:id shard-id-str :data (if (seq caps) (csexp/append envelope (concat ["caps"] caps)) envelope)}))

; the main shard only contains the data and data type - everything else is in the metadata shard
(defn create-with-metadata [contents contenttype labels root-keypair author-keypair server-pubkey cap-keys]
  (let [keyvaluelabels (map seq (seq labels)) ; first seq converts a map to sequence of vectors
        metadata (seq ["metadata" (seq ["timestamp" (str (.now js/Date))]) (seq ["labels" (conj keyvaluelabels "map")])])]
    {:shard (create-shard contents contenttype root-keypair author-keypair cap-keys)
     :metadata (create-shard metadata "c3res/metadata" root-keypair author-keypair (if (some? server-pubkey) [server-pubkey] []))}))

(defn- validate-shard-internal [root-parts]
  (let [stored-id (first root-parts)
        shard (second root-parts)
        signature (second (csexp/decode (nth root-parts 2)))
        calculated-id (.to_hex sodium (.crypto_generichash sodium (.-crypto_generichash_BYTES sodium) shard))]
    (if (not= calculated-id stored-id)
      {:error (str "ID mismatch: calculated " calculated-id ", stored in envelope " stored-id)}
      (let [shard-map (csexp-to-map (csexp/decode shard))
            author-pubkey (.from_hex sodium (:author shard-map))]
        (if (not (.crypto_sign_verify_detached sodium signature calculated-id author-pubkey))
          {:error "Failed to validate shard author's signature"}
          {:id calculated-id :author (:author shard-map)})))))

(defn validate-shard [full-shard]
  (if-let [root-parts (csexp/decode-single-layer full-shard)]
    (validate-shard-internal root-parts)
    {:error "Invalid shard structure; not a valid csexpression"})) 

(defn- get-key-or-nil [cap keypair]
  (try
    (.crypto_box_seal_open sodium cap (:enc-public keypair) (:enc-private keypair))
    (catch js/Error _ nil)))

(defn- get-key-from-caps [root-parts keypair]
  (let [caps (rest (csexp/decode (nth root-parts 3)))]
    (some #(get-key-or-nil % keypair) caps)))

(defn- read-shard-internal [full-shard my-keypair use-caps]
  (let [root-parts (csexp/decode-single-layer full-shard)
        info-or-error (validate-shard-internal root-parts)]
    (if (:error info-or-error)
      info-or-error ; propagate error to caller
      (let [shard-map (csexp-to-map (csexp/decode (second root-parts)))]
        (if-let [stream-key-and-random (if use-caps
                                         (get-key-from-caps root-parts my-keypair)
                                         (get-key-or-nil (:rootcap shard-map) my-keypair))]
          (let [stream-key (.slice stream-key-and-random 0 (/ (.-length stream-key-and-random) 2))
                plaintext (.crypto_secretbox_open_easy sodium (:encrypted shard-map) (:nonce shard-map) stream-key)
                shard-as-map (csexp-to-map (csexp/decode plaintext))]
            (if (= (:type shard-as-map) "c3res/metadata")
              ; special handling for metadata: replace "raw" with pre-processed map
              (let [metadata (csexp-to-map (:raw shard-as-map))
                    shard-without-raw (dissoc shard-as-map :raw)]
                (assoc shard-without-raw :metadata (assoc metadata :labels (apply hash-map (flatten (rest (:labels metadata)))))))
              shard-as-map))
          {:error "Could not extract shard key"})))))

(defn read-shard [full-shard my-keypair]
  (read-shard-internal full-shard my-keypair false))

(defn read-shard-caps [full-shard my-keypair]
  (read-shard-internal full-shard my-keypair true))

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

