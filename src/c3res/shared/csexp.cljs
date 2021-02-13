(ns c3res.shared.csexp
  (:require [cljs.nodejs :as node]
            [clojure.string :as s]))

; Avoiding node.js classes (like Buffer) on purpose to maintain browser compatibility

; TODO: REMOVE ME once you can use up-to-date node version!
(def util (node/require "util"))
(def TextEncoder (.-TextEncoder util))

; XXX: drop this obsolete map and replace with simple "write-string/buffer" and "get-string/buffer-len" functions
(def TYPES {:string [#(.set (js/Uint8Array. (.-buffer %1)) (.encode (TextEncoder.) %3) %2) #(.-length (.encode (TextEncoder.) %))]
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
              (seq? current) (encode-internal current (conj elements {:type :string :value "("}))
              :else elements))
      (conj elements {:type :string :value ")"})))) 

(defn encode [sexp]
  (serialize (encode-internal sexp [{:type :string :value "("}])))

; TODO: support buffers, use TextDecoder for the strings
(defn- decode-single [remaining data]
  (let [[fullmatch group] (re-find #"^(\d+):" (s/join remaining)) ; XXX This string conversion will probably kill performance
        start (nthnext remaining (count fullmatch))
        datalen (js/parseInt group)]
    [(nthnext start datalen) (conj data (s/join (take datalen start)))]))

(defn- decode-internal [csexp existing-data]
  (loop [state :start remaining csexp data existing-data]
    (let [current (first remaining)]
      (if (nil? current)
        data
        (case state
          :start (when (= current "(") (recur :len (rest remaining) data))
          :len (let [[newremaining newdata] (decode-single remaining data)] (recur :next newremaining newdata))
          :next (cond
                  (= current "(")
                  (let [[newremaining newdata] (decode-internal remaining data)] (recur :next newremaining newdata))
                  (= current ")")
                  [(rest remaining) data]
                  :else ; in well-formed csexp this has to be a length string
                  (recur :len remaining data)))))))


; some assumptions we need to make here:
;   - the root sequence contains key-value pairs
;   - each buffer is in its own sequence, starting with atom "buffer"
;   - NOTE: The latter is currently NOT enforced by the above encoding code,
;     that should be refactored once the data format seems meaningful and stable enough
;   - the above also means that we can interpret everything non-buffer as a string
(defn decode [csexp]
  (seq (second (decode-internal csexp []))))

