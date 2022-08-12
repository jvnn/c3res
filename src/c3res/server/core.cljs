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

(defn- validate-arg [key value args]
  (case key
    :key-file (if (s/blank? value) (print "Empty key file path") true)
    :owner (if-not (and (string? value) (re-matches #"^[0-9a-z]{64}$" value)) (print "Invalid owner key format") true)
    :daemon (do (print "Daemon mode not yet implemented...") false)
    :db-root (try (.accessSync fs value) true (catch js/Error _ (print "Invalid db root path")))
    :storage (try (.accessSync fs value) true (catch js/Error _ (print "Invalid storage root path")))))

(defn- validate-args [args]
  (let [required #{:owner :db-root :key-file :storage}
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
        "--storage" (recur (assoc args :storage (second current)) (nnext current))
        (do (print "Invalid argument" (first current)) (.exit process 1))))))

(defn- generate-keys [key-file]
  (let [newkeys (shards/generate-keys)
        keys-csexp (csexp/encode (conj (map #(seq [(name (first %)) (second %)]) newkeys) "serverkeys"))]
    (.writeFileSync fs key-file keys-csexp)
    newkeys))

(defn- read-keys [key-file]
  (let [keys-csexp (csexp/decode (.readFileSync fs key-file))]
    (reduce #(assoc %1 (keyword (first %2)) (second %2)) {} (rest keys-csexp))))


; --------- ^^^^ helpers ---------- vvvv request handling ----------


(defn- store-shard [args expected-id data allowed_authors]
  (go
    (let [id-and-author (shards/validate-shard data)]
      (cond
        (:error id-and-author) (assoc id-and-author :status 400)
        (and (some? expected-id) (not= expected-id (:id id-and-author))) {:status 400 :error "ID mismatch between path and shard"}
        :else
        (if-not (contains? allowed_authors (:author id-and-author))
          {:status 401 :error "Unauthorized author"}
          (if-not (<! (storage/store-shard (:storage args) {:id (:id id-and-author) :data data}))
            {:status 500 :error "Internal error when trying to store the shard"}
            id-and-author)))))
  ; pass id to plugins for data duplication (backup) purposes...?
  )

(defn- store-metadata [args data allowed_authors server-keypair database]
  (go
    (let [result (<! (store-shard args nil data allowed_authors))]
      (if (:error result)
        result ; propagate error
        (if-let [shard (shards/read-shard-caps data server-keypair)]
          (let [metadata (:metadata shard)]
            (db/store-shard-metadata database (:for metadata) (:timestamp metadata) (:author result) (:labels metadata)))
          {:status 400 :error "Invalid metadata shard"}))))
  ; TODO: pass metadata to plugins / extensions for things like federation
  )

(defn- get-shard [args id]
  (go
    (if-let [shard (<! (storage/get-shard (:storage args) id))]
      {:shard shard}
      {:error "Could not find a shard with given ID" :status 404})))

(defn- parse-payload [args payload allowed_authors server-keypair database]
  (go
    (if-let [parts (csexp/decode-single-layer payload)]
      (if (= "c3res-envelope" (first parts))
        (loop [single (first (rest parts)) remaining (rest (rest parts))]
          (if-let [[type contents] (csexp/decode-single-layer single)]
            (let [result (case type
                           "shard" (<! (store-shard args nil contents allowed_authors))
                           "metadata" (<! (store-metadata args contents allowed_authors server-keypair database))
                           {:status 400 :error "Invalid envelope content type"})]
              (if (or (:error result) (nil? (first remaining)))
                result
                (recur (first remaining) (rest remaining))))
            {:status 400 :error "Invalid element in envelope"}))
        {:status 400 :error (str "Unknown payload type: " (first parts))})
      {:status 400 :error "Invalid payload structure"})))

(defn- query-labels [args server-keypair label value database]
  (go
    (let [ids (<! (db/query-labels database label value))
          data (csexp/encode (cons "query-results" (seq ids)))]
      {:result (.from js/Buffer (:data (shards/create-shard data "c3res/csexp" server-keypair server-keypair [(:owner-enc-pubkey args)])))})))

(defn- ret-error [res error-map]
  (.writeHead res (:status error-map) (:error error-map)))

(defn- ret-csexp [res csexp]
  (.writeHead res 200 (clj->js {"Content-Type" "application/octet-stream"
                                "Content-Length" (str (.-length csexp))}))
  (.write res csexp))

(defn -main [& argv]
  (go
    (<! (sod/init))
    (let [sodium (sod/get-sodium)
          args0 (parse-args argv)
          args (assoc args0 :owner-enc-pubkey (.crypto_sign_ed25519_pk_to_curve25519 sodium (.from_hex sodium (:owner args0))))
          database (<! (db/open-db (.join path (:db-root args) (str (:owner args) ".db"))))
          server-keypair (if (not (<! (storage/file-accessible (:key-file args))))
                           (do
                             (print "Non-existing key file path given: generating a new server key pair")
                             (generate-keys (:key-file args)))
                           (do
                             (print "Using existing key file")
                             (read-keys (:key-file args))))]
      (print "Starting server with public key " (.to_hex sodium (:enc-public server-keypair)))
      (let [app (express)
            allowed_authors #{(:owner args)}]
        (.get app "/" (fn [req res] (.send res "Hello C3RES!")))

        (.get app "/shard/:id" (fn [req res] (go
                                               (let [result (<! (get-shard args (.-id (.-params req))))]
                                                 (if (:error result)
                                                   (ret-error res result)
                                                   (ret-csexp res (:shard result)))
                                                 (.end res)))))
        (.post app "/shard" (.raw body-parser) (fn [req res] (go
                                                               (let [error-map (<! (parse-payload args (.-body req) allowed_authors server-keypair database))]
                                                                 (if (:error error-map)
                                                                   (ret-error res error-map)
                                                                   (.writeHead res 200))
                                                                 (.end res)))))
        (.put app "/shard/:id" (.raw body-parser) (fn [req res] (go
                                                                  (let [error-map (<! (store-shard args (.-id (.-params req)) (.-body req) allowed_authors))]
                                                                    (if (:error error-map)
                                                                      (ret-error res error-map)
                                                                      (.writeHead res 200))
                                                                    (.end res)))))

        ; metadata query interface
        ; TODO: this should probably be authenticated somehow to prevent information
        ; exposure via inspecting the size of server responses and DoS attacks by making
        ; the server do a lot of unneccessary work. But as the responses are encrypted
        ; shards with caps only for the owner, there is no direct information leakage
        ; and we can live without auth for now.
        (.get app "/labels/:label" (fn [req res] (go
                                                   (let [result (<! (query-labels args server-keypair (js/decodeURIComponent (.-label (.-params req))) nil database))]
                                                     (if (:error result)
                                                       (ret-error res result)
                                                       (ret-csexp res (:result result)))))))

        (.get app "/labels/:label/:value" (fn [req res] (go
                                                          (let [params (.-params req)
                                                                label (js/decodeURIComponent (.-label params))
                                                                value (js/decodeURIComponent (.-value params))
                                                                result (<! (query-labels args server-keypair label value database))]
                                                            (if (:error result)
                                                              (ret-error res result)
                                                              (ret-csexp res (:result result)))))))

        (.listen app 3001 #(print "Listening on port 3001"))))))

(set! *main-cli-fn* -main)

