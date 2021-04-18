; CONTINUE HERE: create a keystore for storing the main key (xorred from a password) and group membership keys
;   - should be stored as a shard
;   - the shard is keyed via the main key
;   - ... thus the memberships can be shared by devices by sharing the shard like any other

(ns c3res.client.keystore
  (:require [c3res.client.shards :as shards]
            [c3res.client.sodiumhelper :as sod]
            [c3res.client.storage :as storage]
            [clojure.string :as s]
            [cljs.nodejs :as node]
            [cljs.core.async :as async :refer [<!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def sodium (sod/get-sodium))

(defn- combine [key-seed pw-hash]
  ; TODO: bitwise xor will probably work here
  )

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
                                    (:salt key-info)
                                    (:opslimit key-info)
                                    (:memlimit key-info)
                                    (.-crypto_pwhash_ALG_DEFAULT sodium))]
        (combine (:key-seed key-info) pw-hash)))))

