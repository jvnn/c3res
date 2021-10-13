(ns c3res.server.core
  (:require [c3res.shared.storage :as storage]
            [cljs.nodejs :as node]
            [cljs.core.async :as async :refer [<! chan]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(node/enable-util-print!)

(def express (node/require "express"))
(def body-parser (node/require "body-parser"))
(def cookie-parser (node/require "cookie-parser"))

(defn- store-shard [id data]
  (go
    ; TODO: validate id, check author signature
    (if (<! (storage/store-shard "/tmp/c3res" {:id id :data data}))
      200
      500))
  ; pass id to plugins for data duplication (backup) purposes...?
  )

(defn- store-metadata [id data encrypted-key]
  (go
    (<! (store-shard id data)))
  ; TODO:
  ;  - use server priv key to decrypt stream key
  ;  - store metadata into data base
  ;  - pass metadata to plugins / extensions for things like federation
  )

(defn -main [& args]
  (let [app (express)]
    ;(.use app cookie-parser)

    (.get app "/" (fn [req res] (.send res "Hello C3RES!")))

    (.get app "/shard/:id" (fn [req res] (.send res (str "Would return shard " (.-id (.-params req))))))
    (.put app "/shard/:id" (.raw body-parser) (fn [req res] (go
                                                              (.writeHead res (<! (store-shard (.-id (.-params req)) (.-body req))))
                                                              (.end res))))

    (.listen app 3001 #(print "Listening on port 3001"))))

(set! *main-cli-fn* -main)

