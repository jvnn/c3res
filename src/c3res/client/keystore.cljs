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
  (:require-macros [cljs.core.async.macros :refer [go]]))

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
      (<! (storage/store-master-key-input storage-opts
                                          (csexp/encode (seq ["key-seed" (seq ["bin" (js/Uint8Array. (array-xor (:private new-key) pw-hash))])
                                                              "salt" (seq ["bin" salt])
                                                              "opslimit" (str opslimit)
                                                              "memlimit" (str memlimit)
                                                              "public" (seq ["bin" (js/Uint8Array. (:public new-key))])]))))
      new-key)))

; The master key is protected against device compromise by only storing a seed on disk.
; The final key should be extracted by combining the seed and a (slow, brute-force resistant)
; hash of the password. Eventually the password should be stored on a keyring for better
; usability.
(defn get-master-key [storage-opts password-getter]
  (go
    (when-let [master-key-input (<! (storage/get-master-key-input storage-opts))]
      (let [key-info (shards/csexp-to-map (csexp/decode master-key-input))
            user-pw (<! (password-getter))
            pw-hash (.crypto_pwhash sodium
                                    (.-crypto_box_PUBLICKEYBYTES sodium)
                                    user-pw
                                    (second (:salt key-info)) ; XXX make the bloody bin stuff hidden in csexp
                                    (int (:opslimit key-info))
                                    (int (:memlimit key-info))
                                    (.-crypto_pwhash_ALG_DEFAULT sodium))]
        {:public (second (:public key-info)) :private (js/Uint8Array. (array-xor (second (:key-seed key-info)) pw-hash))}))))

