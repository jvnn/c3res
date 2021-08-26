; CONTINUE HERE: create a keystore for storing the main key (xorred from a password) and group membership keys
;   - should be stored as a shard
;   - the shard is keyed via the main key
;   - ... thus the memberships can be shared by devices by sharing the shard like any other

(ns c3res.client.keystore
  (:require [c3res.client.shards :as shards]
            [c3res.client.sodiumhelper :as sod]
            [c3res.client.storage :as storage]
            [c3res.shared.csexp :as csexp]
            [clojure.string :as s]
            [cljs.nodejs :as node]
            [cljs.core.async :as async :refer [<!]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(def sodium (sod/get-sodium))

(defn- array-xor [x y]
  (js/Uint8Array. (map #(bit-xor (first %) (second %)) (partition 2 (interleave x y)))))

; use part of key-seed as salt to allow for regeneration based on key seed string (assuming saltbytes <= seedbytes, true as of now)
(defn- extract-salt [key-seed]
  (.slice key-seed 0 (.-crypto_pwhash_SALTBYTES sodium)))

(defn create-master-key [custom-storage-opts password-getter key-seed-getter]
  (go
    (let [opslimit (.-crypto_pwhash_OPSLIMIT_MODERATE sodium)
          memlimit (.-crypto_pwhash_MEMLIMIT_MODERATE sodium)
          key-seed-str (<! (key-seed-getter))
          key-seed (if (empty? key-seed-str)
                     (.randombytes_buf sodium (.-crypto_box_SEEDBYTES sodium))
                     (.crypto_pwhash sodium
                                     (.-crypto_box_SEEDBYTES sodium)
                                     key-seed-str
                                     (js/Uint8Array. (.-crypto_pwhash_SALTBYTES sodium)) ; all zero salt, as seed string should be enough to recreate
                                     opslimit memlimit (.-crypto_pwhash_ALG_DEFAULT sodium)))
          user-pw (<! (password-getter))
          pw-hash (.crypto_pwhash sodium
                                  (.-crypto_box_PUBLICKEYBYTES sodium)
                                  user-pw
                                  (extract-salt key-seed)
                                  opslimit memlimit (.-crypto_pwhash_ALG_DEFAULT sodium))
          new-keypair (.crypto_box_seed_keypair sodium (array-xor key-seed pw-hash))]
      ; XXX HANDLE ERROR CASE WHEN STORING
      (<! (storage/store-master-key-input custom-storage-opts
                                          (csexp/encode (seq ["master-key"
                                                              (seq ["key-seed" key-seed])
                                                              (seq ["opslimit" (str opslimit)])
                                                              (seq ["memlimit" (str memlimit)])
                                                              (seq ["public" (.-publicKey new-keypair)])]))))
      {:public (.-publicKey new-keypair) :private (.-privateKey new-keypair)})))

; The master key is protected against device compromise by only storing a seed on disk.
; The final key should be extracted by combining the seed and a (slow, brute-force resistant)
; hash of the password. Eventually the password should be stored on a keyring for better
; usability.
(defn get-master-key [custom-storage-opts password-getter]
  (go-loop [msg "An existing master key file found, trying to extract keys..."]
    (if-let [master-key-input (<! (storage/get-master-key-input custom-storage-opts))]
      (do
        (when (not (s/blank? msg)) (print msg))
        (let [key-info (shards/csexp-to-map (csexp/decode master-key-input))
              user-pw (<! (password-getter))
              pw-hash (.crypto_pwhash sodium
                                      (.-crypto_box_PUBLICKEYBYTES sodium)
                                      user-pw
                                      (extract-salt (:key-seed key-info))
                                      (int (:opslimit key-info))
                                      (int (:memlimit key-info))
                                      (.-crypto_pwhash_ALG_DEFAULT sodium))
              keypair (.crypto_box_seed_keypair sodium (array-xor (:key-seed key-info) pw-hash))]
          ; confirm that we really got the correct key pair
          (if (= (vec (:public key-info)) (vec (.-publicKey keypair)))
            {:public (.-publicKey keypair) :private (.-privateKey keypair)}
            (recur "Invalid password for master key, please try again"))))
      false)))

