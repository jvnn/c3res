(ns c3res.server.core
  (:require [c3res.shared.storage :as storage]
            [c3res.shared.shards :as shards]
            [cljs.nodejs :as node]
            [cljs.core.async :as async :refer [<! chan]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(node/enable-util-print!)

(def express (node/require "express"))
(def body-parser (node/require "body-parser"))
(def cookie-parser (node/require "cookie-parser"))

; XXX The server crashes when you send multipart data to it...

(defn- store-shard [id data]
  (go
    (let [error-or-shard (shards/validate-shard id data)]
      (if (:error error-or-shard)
        (assoc error-or-shard :status 400)
        (if (<! (storage/store-shard "/tmp/c3res" {:id id :data data}))
          nil
          {:status 500 :error "Internal error when trying to store the shard"}))))
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
                                                              (if-let [error-map (<! (store-shard (.-id (.-params req)) (.-body req)))]
                                                                (.writeHead res (:status error-map) (:error error-map))
                                                                (.writeHead res 200))
                                                              (.end res))))

    (.listen app 3001 #(print "Listening on port 3001"))))

(set! *main-cli-fn* -main)

