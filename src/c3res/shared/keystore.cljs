; Some design considerations:
;
; There are problems with both, using either a fully random or
; generated-from-human-readable string seeds.  A fully random seed is harder to
; transfer between e.g. air-gapped devices and is not easy to back up using
; analog means, but it "guarantees" no collisions and is impossible for someone
; to guess. A seed generated from a user-provided string might "easily" collide
; if two different people, without knowing each other, just love the same long
; sentence of their favourite book. Someone might also be able to guess the
; seed. On the other hand moving between devices and backing up (e.g. by simply
; marking down some pages in a book) would be simpler.
;
; But especially considering the much shorter key sizes of elliptic curve based
; algorithms, even backing up and restoring (or copying between devices) of
; random keys by typing should be easy enough. Thus we don't allow the
; generation of keys based on user input.
;
; (If you'd like to allow it after all, you can take a look at commit
; 955cb301764047057fbb61fea924e678b1b2f310 for the initial implementation that
; was then partly reverted.)
;
; Another consideration is about where does the password come in. If we would
; just combine the key seed and password hash and generate the master key from
; that, we could even allow user-provided strings as seeds. But the problem
; there is that we can no longer change the password, e.g. in case it might
; have been compromized. Thus we should first generate the key and then xor it
; with the password hash to generate the key seed stored on disk. Then one can
; retrieve the key with the old password and write it back with a new one. Or
; one can use device-specific passwords for key storage.
;
; Also, as crypto boxes and elliptic curve signing algorithms use different
; types of keys, we actually need two sets of keys to encrypt capabilities and
; to sign shards. There are basically two alternatives: either to concatenate
; the public keys to a single "public" key (to be used as the identity) or to
; use the signing keys as identity and calculate the box encryption keys from
; them (it's only possible in that direction). To keep the identity key size as
; small as possible, the latter option was chosen.

; TODO:
;   - store group memberships (should be stored as a shard)
;     - the shard is keyed via the main key
;     - ... thus the memberships can be shared by devices by sharing the shard like any other
;   - store individual identities (additional key pairs) again as a shard for the above reasons

(ns c3res.shared.keystore
  (:require [c3res.shared.shards :as shards]
            [c3res.shared.sodiumhelper :as sod]
            [c3res.shared.csexp :as csexp]
            [c3res.shared.storage :as storage]
            [clojure.string :as s]
            [cljs.nodejs :as node]
            [cljs.core.async :as async :refer [<!]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(def sodium (sod/get-sodium))

(defn- array-xor [x y]
  (js/Uint8Array. (map #(bit-xor (first %) (second %)) (partition 2 (interleave x y)))))

(defn create-master-key [master-key-input-path password-getter]
  (go
    (let [opslimit (.-crypto_pwhash_OPSLIMIT_MODERATE sodium)
          memlimit (.-crypto_pwhash_MEMLIMIT_MODERATE sodium)
          salt (.randombytes_buf sodium (.-crypto_pwhash_SALTBYTES sodium))
          user-pw (<! (password-getter))
          pw-hash (.crypto_pwhash sodium
                                  (.-crypto_sign_SECRETKEYBYTES sodium)
                                  user-pw salt
                                  opslimit memlimit
                                  (.-crypto_pwhash_ALG_DEFAULT sodium))
          new-keys (shards/generate-keys)]
      ; XXX HANDLE ERROR CASE WHEN STORING
      (<! (storage/store-file master-key-input-path
                              (csexp/encode (seq ["master-key"
                                                  (seq ["key-seed" (array-xor (:sign-private new-keys) pw-hash)])
                                                  (seq ["salt" salt])
                                                  (seq ["opslimit" (str opslimit)])
                                                  (seq ["memlimit" (str memlimit)])
                                                  (seq ["public" (:sign-public new-keys)])]))))
      new-keys)))

; The master key is protected against device compromise by only storing a seed on disk.
; The final key should be extracted by combining the seed and a (slow, brute-force resistant)
; hash of the password. Eventually the password should be stored on a keyring for better
; usability.
(defn get-master-key [master-key-input-path password-getter]
  (go-loop [msg "An existing master key file found, trying to extract keys..."]
    (if-let [master-key-input (<! (storage/get-file master-key-input-path))]
      (do
        (print msg)
        (let [key-info (shards/csexp-to-map (csexp/decode master-key-input))
              user-pw (<! (password-getter))
              pw-hash (.crypto_pwhash sodium
                                      (.-crypto_sign_SECRETKEYBYTES sodium)
                                      user-pw
                                      (:salt key-info)
                                      (int (:opslimit key-info))
                                      (int (:memlimit key-info))
                                      (.-crypto_pwhash_ALG_DEFAULT sodium))
              priv-key (array-xor (:key-seed key-info) pw-hash)]
          ; test that we really got a working key pair
          (if-let [checked-keypair (try
                                     (.crypto_sign_verify_detached sodium (.crypto_sign_detached sodium "test" priv-key) "test" (:public key-info))
                                     {:sign-public (:public key-info) :sign-private priv-key}
                                     (catch js/Error msg (print msg)))]
            (assoc checked-keypair
                   :enc-public (.crypto_sign_ed25519_pk_to_curve25519 sodium (:sign-public checked-keypair))
                   :enc-private (.crypto_sign_ed25519_sk_to_curve25519 sodium (:sign-private checked-keypair)))
            (recur "Invalid password for master key, please try again"))))
      false)))

