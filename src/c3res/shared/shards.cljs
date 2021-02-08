(ns c3res.shared.shards
  (:require [c3res.shared.csexp :as csexp]
            [cljs.nodejs :as node]
            [cljs.core.async :as async :refer [chan close!]]
            ))

(def sodium (node/require "libsodium-wrappers"))

; libsodium needs to be ready before it can be used -> use this before starting
(defn init []
  (let [done (chan)]
    ; ugly conversion from a js Promise to core.async world, if you need this stuff
    ; more often, consider cljs-promises or other libraries
    (.then (.-ready sodium) #(close! done))
      done))

(defn create-shard [plaintext author-priv-key author-pub-key]
  ; TODO: shards should be csexpr encoded, and include at least:
  ;   - creation timestamp
  ;   - initial tags (used for identifying the purpose / origin of this shard in case of loss of metadata / services)
  ;   - type of content (either as separate field or as csexpr type hint)
  ;   - plaintext data given to this function
  (let [stream-key (.crypto_secretbox_keygen sodium (.-crypto_secretbox_KEYBYTES sodium))
        nonce (.randombytes_buf sodium (.-crypto_secretbox_NONCEBYTES sodium))
        box (.crypto_secretbox_easy sodium plaintext nonce stream-key)
        author-cap (.crypto_box_seal sodium stream-key author-pub-key)
        shard (.concat author-cap (.concat nonce box))
        shard-id (.crypto_generichash sodium (.crypto_generichash_BYTES sodium) shard)]
    {:shard-id shard-id :shard shard}
    ))

