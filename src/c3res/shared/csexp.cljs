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

(defn- get-length [element]
  (let [lenfn (last (TYPES (:type element)))]
    (lenfn (:value element))))

(defn- insert-element [buf element offset]
  (let [writefun (first (TYPES (:type element)))
        size (get-length element)
        val (:value element)]
    (writefun buf offset val)
    (+ offset size)))

(defn- buf-size [data]
  (reduce #(+ %1 (get-length %2)) 0 data))

(defn serialize [data]
  (let [buf (js/ArrayBuffer. (buf-size data))
        view (js/DataView. buf)]
    (loop [element (first data) remaining (rest data) offset 0]
      (if element
        (recur (first remaining) (rest remaining) (insert-element view element offset))
        buf))))

(defn- add-length [len elements]
  (conj elements {:type :string :value (str len)} {:type :string :value ":"}))

(defn- encode-internal [sexp current-elements]
  (loop [current (first sexp) remaining (rest sexp) elements current-elements]
    (if (some? current)
      (recur (first remaining) (rest remaining)
             (cond
              (string? current) (conj (add-length (count current) elements) {:type :string :value current})
              (instance? js/Uint8Array current) (conj (add-length (.-length current) elements) {:type :buffer :value current})
              (seq? current) (encode-internal current (conj elements {:type :string :value "("}))))
      (conj elements {:type :string :value ")"})))) 

(defn encode [sexp]
  (serialize (encode-internal sexp [{:type :string :value "("}])))

