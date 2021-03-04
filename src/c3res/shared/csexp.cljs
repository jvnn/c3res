(ns c3res.shared.csexp
  (:require [cljs.nodejs :as node]
            [clojure.string :as s]))

; Avoiding node.js classes (like Buffer) on purpose to maintain browser compatibility

; TODO: REMOVE ME once you can use up-to-date node version!
(def util (node/require "util"))
(def TextEncoder (.-TextEncoder util))
(def TextDecoder (.-TextDecoder util))

; XXX: remove unnecessary double TextEncoder usage
(defn- put-str [buffer string len offset]
  (.set (js/Uint8Array. (.-buffer buffer)) (.encode (TextEncoder.) string) offset)
  (+ offset len))

(defn- str-len [string]
  (.-length (.encode (TextEncoder.) string)))

(defn- insert-string [buffer string offset]
  (let [strlen (str-len string)
        strlenstr (str strlen)
        offset-after-len (put-str buffer strlenstr (count strlenstr) offset)
        offset-after-colon (put-str buffer ":" 1 offset-after-len)]
    (put-str buffer string strlen offset-after-colon)))

(defn- insert-buffer [buffer toinsert offset]
  (let [buflen (.-length toinsert)
        buflenstr (str buflen)
        offset-after-len (put-str buffer buflenstr (count buflenstr) offset)
        offset-after-colon (put-str buffer ":" 1 offset-after-len)]
    (.set (js/Uint8Array. (.-buffer buffer)) toinsert offset-after-colon)
    (+ offset-after-colon buflen)))

(defn- encode-internal [sexp buffer start-offset]
  (loop [current (first sexp) remaining (rest sexp) offset start-offset]
    (if (some? current)
      (recur (first remaining) (rest remaining)
             (cond
              (string? current) (insert-string buffer current offset)
              (instance? js/Uint8Array current) (insert-buffer buffer current offset)
              (seq? current) (encode-internal current buffer (put-str buffer "(" 1 offset))
              :else (throw "unexpected type")))
      (put-str buffer ")" 1 offset))))

(defn- get-length [element]
  (if (seq? element)
    (reduce #(+ %1 (get-length %2)) 2 element) ; 2 for "(" and ")"
    (let [element-len (cond
                        (string? element) (str-len element)
                        (instance? js/Uint8Array element) (.-length element))
          lenstrlen (count (str element-len))]
      (+ element-len lenstrlen 1)))) ; +1 for colon

(defn- buf-size [data]
  (reduce #(+ %1 (get-length %2)) 2 data)) ; 2 for ( and )

(defn encode [sexp]
  (let [buf (js/ArrayBuffer. (buf-size sexp))
        view (js/DataView. buf)]
    (encode-internal sexp view (put-str view "(" 1 0))
    (js/Uint8Array. buf)))

(defn- get-int [string]
  (let [parsed (js/parseFloat string)]
    (if (integer? parsed) parsed nil)))

(defn- as-char [element]
  (if (number? element)
    (.fromCharCode js/String element)
    element))

(defn- as-number [element]
  (if (number? element)
    element
    (.charCodeAt element 0)))

(defn- decode-single [remaining data]
  (when (get-int (as-char (first remaining)))
    (let [len-string (take-while #(get-int (as-char %)) remaining)
          datalen (get-int (s/join (map as-char len-string)))
          stringlen (count len-string)
          nextchar (as-char (nth remaining stringlen))
          start (nthnext remaining (+ stringlen 1))
          uint8buf (js/Uint8Array. (map as-number (take datalen start)))
          isbuffer (and (= (count data) 1) (= (first data) "bin"))]
      (when (= nextchar ":")
        (if isbuffer
          [(nthnext start datalen) (conj data uint8buf)]
          [(nthnext start datalen) (conj data (.decode (TextDecoder.) uint8buf))])))))

(defn- decode-internal [csexp]
  (loop [state :start remaining csexp data []]
    (when-let [current (as-char (first remaining))]
      (case state
        :start (when (= current "(") (recur :len (rest remaining) data))
        :len (let [[newremaining newdata] (decode-single remaining data)] (recur :next newremaining newdata))
        :next (cond
                (= current "(")
                (let [[newremaining newdata] (decode-internal remaining)] (recur :next newremaining (conj data (seq newdata))))
                (= current ")")
                [(rest remaining) data]
                :else ; in well-formed csexp this has to be a length string
                (recur :len remaining data))))))


; some assumptions we need to make here:
;   - the root sequence contains key-value pairs
;   - each binary buffer is in its own sequence, starting with atom "bin"
;   - NOTE: The latter is currently NOT enforced by the above encoding code,
;     that should be refactored once the data format seems meaningful and stable enough
;   - the above also means that we can interpret everything non-buffer as a string
(defn decode [csexp]
  (seq (second (decode-internal csexp))))

