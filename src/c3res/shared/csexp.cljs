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
              (instance? js/Uint8Array current) (->> offset
                                                     (put-str buffer "(" 1)
                                                     (insert-string buffer "!bin")
                                                     (insert-buffer buffer current)
                                                     (put-str buffer ")" 1))
              (seq? current) (encode-internal current buffer (put-str buffer "(" 1 offset))
              :else (throw "unexpected type")))
      (put-str buffer ")" 1 offset))))

(defn- get-length [element]
  (if (seq? element)
    (reduce #(+ %1 (get-length %2)) 2 element) ; 2 for "(" and ")"
    (let [isbuffer (instance? js/Uint8Array element)
          element-len (cond
                        (string? element) (str-len element)
                        isbuffer (.-length element))
          lenstrlen (count (str element-len))]
      (+ element-len lenstrlen 1 (if isbuffer 8 0))))) ; +1 for colon, +8 for "(4:!bin" and ")" 

(defn- buf-size [data]
  (reduce #(+ %1 (get-length %2)) 2 data)) ; 2 for ( and )

(defn encode [sexp]
  (let [buf (js/ArrayBuffer. (buf-size sexp))
        view (js/DataView. buf)]
    (encode-internal sexp view (put-str view "(" 1 0))
    (js/Uint8Array. buf)))

; -----------------------------------------------------------------------------

; Append a seq or a single value (string / buffer) to the end of an existing csexp
(defn append [csexp toappend]
  (let [isseq (seq? toappend)
        newbuf (js/ArrayBuffer. (+ (.-length csexp) (if isseq (buf-size toappend) (get-length toappend))))
        uint8buf (js/Uint8Array. newbuf)
        view (js/DataView. newbuf)]
    (.set uint8buf csexp)
    ; overwrite the last ")" of the previous buffer (thus offset = old len - 1)
    (if isseq
      (do
        (encode-internal toappend view (put-str view "(" 1 (- (.-length csexp) 1)))
        ; reinsert the last ")" (done by encode-internal for non-seq values)
        (put-str view ")" 1 (- (.-length uint8buf) 1))))
      (encode-internal (seq [toappend]) view (- (.-length csexp) 1))
    uint8buf))

; Wrap an existing encoded csexp to another layer: (wrap (encode '("bar") "foo") --> ("foo" ("bar"))
; (Can't use append here as that would treat the existing csexp as a buffer and add the !bin handling to it)
(defn wrap [csexp wrapper-value]
  (let [wrapper-val-len (get-length wrapper-value)
        newbuf (js/ArrayBuffer. (+ (.-length csexp) wrapper-val-len 2)) ; 2 for "(" and ")"
        uint8buf (js/Uint8Array. newbuf)
        view (js/DataView. newbuf)]
    (encode-internal (seq [wrapper-value]) view (put-str view "(" 1 0))
    (.set uint8buf csexp (+ wrapper-val-len 1))
    (put-str view ")" 1 (- (.-length uint8buf) 1))
    uint8buf))

; -----------------------------------------------------------------------------

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
          isbuffer (and (= (count data) 1) (= (first data) "!bin"))]
      (when (= nextchar ":")
        (if isbuffer
          [(nthnext start datalen) uint8buf] ; drop the "!bin" marker
          [(nthnext start datalen) (conj data (.decode (TextDecoder.) uint8buf))])))))

(defn- slurp-subseq [input]
  (loop [remaining input parens 0 len 1] ; len == how many characters have been checked _including_ current
    (when-let [current (as-char (first remaining))]
      (cond
        (= current "(")
        (recur (rest remaining) (inc parens) (inc len))
        (= current ")")
        (if (= parens 1)
          [(nthnext input len) (js/Uint8Array. (map as-number (take len input)))]
          (recur (rest remaining) (dec parens) (inc len)))
        :else
        (recur (rest remaining) parens (inc len))))))

(defn- decode-internal [csexp single-layer]
  (loop [state :start remaining csexp data []]
    (when-let [current (as-char (first remaining))]
      (case state
        :start (when (= current "(") (recur :len (rest remaining) data))
        :len (let [[newremaining newdata] (decode-single remaining data)] (recur :next newremaining newdata))
        :next (cond
                (= current "(")
                (if single-layer
                  (let [[newremaining subseq-buffer] (slurp-subseq remaining)] (recur :next newremaining (conj data subseq-buffer)))
                  (let [[newremaining newdata] (decode-internal remaining false)]
                    (recur :next newremaining
                           ; check for single binary buffer (decoded from "!bin" entry), and don't seq it
                           (conj data (if (instance? js/Uint8Array newdata)
                                        newdata
                                        (seq newdata))))))
                (= current ")")
                [(rest remaining) data]
                :else ; in well-formed csexp this has to be a length string
                (recur :len remaining data))))))

(defn decode [csexp]
  (seq (second (decode-internal csexp false))))

; -----------------------------------------------------------------------------

; split the topmost layer of this csexp into individual components, leave subseqs undecoded:
; (decode-single-layer (encode '("foo" ("bar" "baz") "boom"))) --> ("foo" #js/Uint8Array[(3:bar3:baz)] "boom")
;   --> note how leaf values are fully decoded, but nested sequences are left in their encoded form
(defn decode-single-layer [csexp]
  (seq (second (decode-internal csexp true))))

