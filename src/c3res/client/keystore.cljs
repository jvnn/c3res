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
  (map #(bit-xor (first %) (second %)) (partition 2 (interleave x y))))

(defn create-master-key [storage-opts password-getter]
  (go
    (let [new-key (shards/generate-keys)
          user-pw (<! (password-getter))
          salt (.randombytes_buf sodium (.-crypto_pwhash_SALTBYTES sodium))
          opslimit (.-crypto_pwhash_OPSLIMIT_MODERATE sodium)
          memlimit (.-crypto_pwhash_MEMLIMIT_MODERATE sodium)
          pw-hash (.crypto_pwhash sodium
                                  (.-crypto_box_PUBLICKEYBYTES sodium)
                                  user-pw salt opslimit memlimit
                                  (.-crypto_pwhash_ALG_DEFAULT sodium))]
      ; XXX HANDLE ERROR CASE WHEN STORING
      (<! (storage/store-master-key-input storage-opts
                                          (csexp/encode (seq ["key-seed" (js/Uint8Array. (array-xor (:private new-key) pw-hash))
                                                              "salt" salt
                                                              "opslimit" (str opslimit)
                                                              "memlimit" (str memlimit)
                                                              "public" (:public new-key)]))))
      new-key)))

; The master key is protected against device compromise by only storing a seed on disk.
; The final key should be extracted by combining the seed and a (slow, brute-force resistant)
; hash of the password. Eventually the password should be stored on a keyring for better
; usability.
(defn get-master-key [storage-opts password-getter]
  (go-loop [msg "An existing master key file found, trying to extract keys..."]
    (if-let [master-key-input (<! (storage/get-master-key-input storage-opts))]
      (do
        (when (not (s/blank? msg)) (print msg))
        (let [key-info (shards/csexp-to-map (csexp/decode master-key-input))
              user-pw (<! (password-getter))
              pw-hash (.crypto_pwhash sodium
                                      (.-crypto_box_PUBLICKEYBYTES sodium)
                                      user-pw
                                      (:salt key-info)
                                      (int (:opslimit key-info))
                                      (int (:memlimit key-info))
                                      (.-crypto_pwhash_ALG_DEFAULT sodium))
              priv-key (js/Uint8Array. (array-xor (:key-seed key-info) pw-hash))]
          ; test that we really got a working key pait
          (if-let [checked-keypair (try
                                     (.crypto_box_seal_open sodium (.crypto_box_seal sodium "test" (:public key-info)) (:public key-info) priv-key)
                                     {:public (:public key-info) :private priv-key}
                                     (catch js/Error))]
            checked-keypair
            (recur "Invalid password for master key, please try again"))))
      false)))

