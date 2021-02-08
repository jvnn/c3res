(ns c3res.shared.csexp
  (:require [cljs.nodejs :as node]))

; Avoiding node.js classes (like Buffer) on purpose to maintain browser compatibility

; TODO: REMOVE ME once you can use up-to-date node version!
(def util (node/require "util"))
(def TextEncoder (.-TextEncoder util))

(def TYPES {:int32 [#(.setInt32 %1 %2 %3) #(int 4)]
            :uint32 [#(.setUint32 %1 %2 %3) #(int 4)]
            :string [#(.set (js/Uint8Array. (.-buffer %1)) (.encode (TextEncoder.) %3) %2) #(.-length (.encode (TextEncoder.) %))]
            :buffer [#(.set (js/Uint8Array. (.-buffer %1)) %3 %2) #(.-length %)]})  

(defn- insert-element [buf element offset]
  (let [typeinfo (TYPES (:type element))
        writefun (first typeinfo)
        val (:value element)
        size ((last typeinfo) val) ]
    (writefun buf offset val)
    (+ offset size)))

(defn- buf-size [data]
  (let [lenfn #(last (TYPES (:type %)))]
    (reduce #(+ %1 ((lenfn %2) (:value %2))) 0 data)))

(defn encode [data]
  (let [buf (js/ArrayBuffer. (buf-size data))
        view (js/DataView. buf)]
    (loop [element (first data) remaining (rest data) offset 0]
      (if element
        (recur (first remaining) (rest remaining) (insert-element view element offset))
        buf))))
