(ns c3res.shared.sodiumhelper
  (:require [cljs.nodejs :as node]
            [cljs.core.async :as async :refer [chan close!]]))

(def sodium (node/require "libsodium-wrappers"))

; libsodium needs to be ready before it can be used -> use this before starting
(defn init []
  (let [done (chan)]
    ; ugly conversion from a js Promise to core.async world, if you need this stuff
    ; more often, consider cljs-promises or other libraries
    (.then (.-ready sodium) #(close! done))
      done))

(defn get-sodium []
  ; meant to be used to separate browser usage from node.js
  (node/require "libsodium-wrappers"))
