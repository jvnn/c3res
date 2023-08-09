(ns c3res.client.http
  (:require [clojure.string :as s]
            [cljs.nodejs :as node]
            [cljs.core.async :as async :refer [<! >! put! chan close!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def httpx {"http" (node/require "http")
            "https" (node/require "https")})

(defn- handle-response [res c-status c-data]
  (let [status (.-statusCode res)]
    (go (>! c-status status))
    (when-not (= status 200) (go (>! c-data (.-statusMessage res))))
    (.on res "data" #(go (>! c-data (js/Uint8Array. %))))
    (.on res "end" #(go (>! c-data :end)))))

(defn- handle-error [msg c-out]
  (print msg)
  (close! c-out))

(defn- receive-full-data [c-status c-resp c-out]
  (go
    (let [status (<! c-status)]
      (loop [data (js/Uint8Array. [])]
        (let [chunk (<! c-resp)]
          (cond
            (nil? chunk)
            (do
              (close! c-resp)
              (>! c-out {:status status :data nil}))
            (= :end chunk)
            (do
              (close! c-resp)
              (>! c-out {:status status :data data}))
            :else
            (let [newarray (js/Uint8Array. (+ (.-length data) (.-length chunk)))]
              (.set newarray data)
              (.set newarray chunk (.-length data))
              (recur newarray)))))))
  c-out)

(defn- add-data-headers [headers data]
  (assoc headers
         "Content-Type" "application/octet-stream"
         "Content-Length" (str (.-length data))))

(defn- do-http-request [protocol host port path data headers method]
  (let [c-status (chan)
        c-resp (chan)
        c-out (chan)
        extended-headers (if data (add-data-headers headers data) headers)
        options {"host" host
                 "port" (str port)
                 "path" path
                 "method" method,
                 "headers" extended-headers}
        req (.request (httpx protocol) (clj->js options) #(handle-response % c-status c-resp))]
    (.on req "error" #(handle-error % c-out))
    (when data (.write req data))
    (.end req)
    (receive-full-data c-status c-resp c-out)))

(defn do-get [protocol host port path]
  (do-http-request protocol host port path nil nil "GET"))

(defn do-post [protocol host port path body headers]
  (do-http-request protocol host port path body headers "POST"))

(defn do-put [protocol host port path body headers]
  (do-http-request protocol host port path body headers "PUT"))

(defn do-patch [protocol host port path body headers]
  (do-http-request protocol host port path body headers "PATCH"))

(defn do-delete [protocol host port path body headers]
  (do-http-request protocol host port path body headers "DELETE"))
