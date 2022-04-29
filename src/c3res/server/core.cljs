(ns c3res.server.core
  (:require [c3res.server.db :as db]
            [c3res.shared.csexp :as csexp]
            [c3res.shared.shards :as shards]
            [c3res.shared.sodiumhelper :as sod]
            [c3res.shared.storage :as storage]
            [cljs.nodejs :as node]
            [cljs.core.async :as async :refer [<! chan]]
            [clojure.string :as s]
            [clojure.set :as cset])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(node/enable-util-print!)

(def fs (node/require "fs"))
(def path (node/require "path"))
(def process (node/require "process"))

(def express (node/require "express"))
(def body-parser (node/require "body-parser"))
(def cookie-parser (node/require "cookie-parser"))

; XXX The server crashes when you send multipart data to it...

(defn- store-shard [expected-id data allowed_authors]
  (go
    (let [id-and-author (shards/validate-shard data)]
      (cond
        (:error id-and-author) (assoc id-and-author :status 400)
        (and (some? expected-id) (not= expected-id (:id id-and-author))) {:status 400 :error "ID mismatch between path and shard"}
        :else
        (if-not (contains? allowed_authors (:author id-and-author))
          {:status 401 :error "Unauthorized author"}
          (when-not (<! (storage/store-shard "/tmp/c3res" {:id (:id id-and-author) :data data}))
            {:status 500 :error "Internal error when trying to store the shard"})))))
  ; pass id to plugins for data duplication (backup) purposes...?
  )

(defn- store-metadata [data allowed_authors server-keypair db]
  (go
    (let [result (<! (store-shard nil data allowed_authors))]
      (if (:error result)
        result ; propagate error
        (if-let [shard (shards/read-shard-caps data server-keypair)]
          (print shard)
          {:status 400 :error "Invalid metadata shard"}))))
  ; TODO:
  ;  - store metadata into database
  ;  - pass metadata to plugins / extensions for things like federation
  )

(defn- parse-payload [payload allowed_authors server-keypair db]
  (go
    (if-let [parts (csexp/decode-single-layer payload)]
      (if (= "c3res-envelope" (first parts))
        (loop [single (first (rest parts)) remaining (rest (rest parts))]
          (if-let [[type contents] (csexp/decode-single-layer single)]
            (let [result (case type
                           "shard" (<! (store-shard nil contents allowed_authors))
                           "metadata" (<! (store-metadata contents allowed_authors server-keypair db))
                           {:status 400 :error "Invalid envelope content type"})]
              (if (or (some? result) (nil? (first remaining)))
                result
                (recur (first remaining) (rest remaining))))
            {:status 400 :error "Invalid element in envelope"}))
        {:status 400 :error (str "Unknown payload type: " (first parts))})
      {:status 400 :error "Invalid payload structure"})))

(defn- validate-arg [key value args]
  (case key
    :key-file (if (s/blank? value) (print "Empty key file path") true)
    :owner (if-not (and (string? value) (re-matches #"^[0-9a-z]{64}$" value)) (print "Invalid owner key format") true)
    :daemon (do (print "Daemon mode not yet implemented...") false)
    :db-root (try (.accessSync fs value) true (catch js/Error _ (print "Invalid db root path")))))

(defn- validate-args [args]
  (let [required #{:owner :db-root :key-file}
        have (set (keys args))]
    (when-not (cset/subset? required have)
      (print "Missing mandatory arguments:" (s/join ", " (map #(str "--" (name %)) (cset/difference required have))))
      (.exit process 1)))
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
        "--key-file" (recur (assoc args :key-file (second current)) (nnext current))
        "--owner" (recur (assoc args :owner (second current)) (nnext current))
        "--daemon" (recur (assoc args :daemon true) (next current))
        "--db-root" (recur (assoc args :db-root (second current)) (nnext current))
        (do (print "Invalid argument" (first current)) (.exit process 1))))))

(defn- generate-keys [key-file]
  (let [newkeys (shards/generate-keys)
        keys-csexp (csexp/encode (conj (map #(seq [(name (first %)) (second %)]) newkeys) "serverkeys"))]
    (.writeFileSync fs key-file keys-csexp)
    newkeys))

(defn- read-keys [key-file]
  (let [keys-csexp (csexp/decode (.readFileSync fs key-file))]
    (reduce #(assoc %1 (keyword (first %2)) (second %2)) {} (rest keys-csexp))))

(defn- ret-error [res error-map]
  (.writeHead res (:status error-map) (:error error-map)))

(defn -main [& argv]
  (go
    (<! (sod/init))
    (let [args (parse-args argv)
          db (db/open-db (.join path (:db-root args) (str (:owner args) ".db")))
          server-keypair (if (not (<! (storage/file-accessible (:key-file args))))
                           (do
                             (print "Non-existing key file path given: generating a new server key pair")
                             (generate-keys (:key-file args)))
                           (do
                             (print "Using existing key file")
                             (read-keys (:key-file args))))]
      (print "Starting server with public key " (.to_hex (sod/get-sodium) (:enc-public server-keypair)))
      (let [app (express)
            allowed_authors #{(:owner args)}]
        (.get app "/" (fn [req res] (.send res "Hello C3RES!")))

        (.get app "/shard/:id" (fn [req res] (.send res (str "Would return shard " (.-id (.-params req))))))
        (.post app "/shard" (.raw body-parser) (fn [req res] (go
                                                               (if-let [error-map (<! (parse-payload (.-body req) allowed_authors server-keypair db))]
                                                                 (ret-error res error-map)
                                                                 (.writeHead res 200))
                                                               (.end res))))
        (.put app "/shard/:id" (.raw body-parser) (fn [req res] (go
                                                                  (if-let [error-map (<! (store-shard (.-id (.-params req)) (.-body req) allowed_authors))]
                                                                    (ret-error res error-map)
                                                                    (.writeHead res 200))
                                                                  (.end res))))

        (.listen app 3001 #(print "Listening on port 3001"))))))

(set! *main-cli-fn* -main)

