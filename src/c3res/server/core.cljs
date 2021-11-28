(ns c3res.server.core
  (:require [c3res.shared.shards :as shards]
            [c3res.shared.sodiumhelper :as sod]
            [c3res.shared.storage :as storage]
            [cljs.nodejs :as node]
            [cljs.core.async :as async :refer [<! chan]]
            [clojure.string :as s])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(node/enable-util-print!)

(def process (node/require "process"))
(def express (node/require "express"))
(def body-parser (node/require "body-parser"))
(def cookie-parser (node/require "cookie-parser"))

; XXX The server crashes when you send multipart data to it...

(def allowed_authors (atom #{}))

(defn- store-shard [id data]
  (go
    (let [error-or-shard (shards/validate-shard id data)]
      (if (:error error-or-shard)
        (assoc error-or-shard :status 400)
        (if-not (contains? @allowed_authors (:author error-or-shard))
          (do (print (:author error-or-shard) @allowed_authors) {:status 401 :error "Unauthorized author"})
          (if (<! (storage/store-shard "/tmp/c3res" {:id id :data data}))
            nil
            {:status 500 :error "Internal error when trying to store the shard"})))))
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

(defn- validate-arg [key value args]
  (case key
    :owner (if-not (and (string? value) (re-matches #"^[0-9a-z]{64}$" value)) (print "Invalid owner key format") true)
    :daemon (do (print "Daemon mode not yet implemented...") false)))

(defn- validate-args [args]
  (loop [option-keys (keys args)]
    (if-let [key (first option-keys)]
      (if (validate-arg key (args key) args)
        (recur (rest option-keys))
        (.exit process 1))
      args)))

(defn- parse-args [argv]
  (loop [args {} current argv]
    (if (not current)
      (validate-args args)
      (case (first current)
        "--owner" (recur (assoc args :owner (second current)) (nnext current))
        "--daemon" (recur (assoc args :daemon true) (next current))
        (do (print "Invalid argument" (first current)) (.exit process 1))))))

(defn -main [& argv]
  (go
    (<! (sod/init))
    (let [args (parse-args argv)
          app (express)]
      (if-let [owner (:owner args)]
        (reset! allowed_authors #{owner})
        (do (print "--owner parameter is required to start the server") (.exit process 1)))

      (.get app "/" (fn [req res] (.send res "Hello C3RES!")))

      (.get app "/shard/:id" (fn [req res] (.send res (str "Would return shard " (.-id (.-params req))))))
      (.put app "/shard/:id" (.raw body-parser) (fn [req res] (go
                                                                (if-let [error-map (<! (store-shard (.-id (.-params req)) (.-body req)))]
                                                                  (.writeHead res (:status error-map) (:error error-map))
                                                                  (.writeHead res 200))
                                                                (.end res))))

      (.listen app 3001 #(print "Listening on port 3001")))))

(set! *main-cli-fn* -main)

