(ns c3res.client.core
  (:require [c3res.client.cache :as cache]
            [c3res.client.servercomm :as servercomm]
            [c3res.shared.keystore :as keystore]
            [c3res.shared.sodiumhelper :as sod]
            [c3res.shared.shards :as shards]
            [c3res.shared.storage :as storage]
            [clojure.string :as s]
            [cljs.nodejs :as node]
            [cljs.core.async :as async :refer [<! >! put! chan close!]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(node/enable-util-print!)
(def prompt (node/require "prompt"))
(set! (.-message prompt) "")

(def fs (node/require "fs"))
(def http (node/require "http"))
(def os (node/require "os"))
(def path (node/require "path"))
(def process (node/require "process"))

(defn- get-config-dir [args]
  (or (:config-dir args) (.join path (.homedir os) ".c3res")))

(defn- get-server-config-file-path [config-dir]
  (.join path config-dir "server-config.json"))

(defn- get-master-key-input-path [args]
  (.join path (get-config-dir args) "master-key-input"))

(defn- get-shard-cache-path [args]
  (.join path (get-config-dir args) "cache"))

(defn- confirm-password-interactive-internal [to-confirm]
  (let [c (chan)
        schema (clj->js {"properties" {"password" {"hidden" true
                                                   "replace" "*"
                                                   "required" true
                                                   "description" "Confirm password"}}})]
    (.start prompt)
    (.get prompt schema #(if (= (.-password %2) to-confirm)
                           (go (>! c (.-password %2)))
                           (do
                             (print "Inserted strings do not match, please try again.")
                             (close! c))))
    c))

(defn- get-password-interactive-internal [do-confirm c]
  (let [schema (clj->js {"properties" {"password" {"hidden" true
                                                   "replace" "*"
                                                   "required" true
                                                   "description" "Insert password"}}})]
    (.start prompt)
    (.get prompt schema (if do-confirm
                          #(go
                             (if-let [result (<! (confirm-password-interactive-internal (.-password %2)))]
                               (>! c result)
                               (get-password-interactive-internal do-confirm c)))
                          #(go (>! c (.-password %2)))))
    c))

(defn- get-password-interactive []
  (get-password-interactive-internal false (chan)))

(defn- create-password-interactive []
  (get-password-interactive-internal true (chan)))

(defn- get-or-create-master-key [args]
  (let [pw-getter (if (:password args) #(go (:password args)) get-password-interactive)
        master-key-input-path (get-master-key-input-path args)]
    (go-loop []
      (let [master-key (<! (keystore/get-master-key master-key-input-path pw-getter))]
        (if (:error master-key)
          (case (:error master-key)
            :password (if (= pw-getter get-password-interactive)
                        (do (print "Invalid password, please try again.") (recur))
                        (do (print "Invalid password - terminating") (.exit process 1)))
            :nokey (do
                     (print "No master key detected, creating a new one.")
                     (<! (keystore/create-master-key master-key-input-path create-password-interactive))))
          master-key)))))

(defn- is-hash-64 [possible-hash]
  (re-matches #"[0-9a-f]{64}" possible-hash))

(defn- validate-arg [key value args]
  (go
    (case key
      :config-dir (if-not (<! (storage/ensure-dir value 0750)) (print (str "Invalid config directory '" value "' given")) true)
      :password (if (s/blank? value) (print "Invalid or missing password") true)
      :store (if-not (<! (storage/file-accessible value)) (print (str "Cannot find or access file '" value "' to store")) true)
      :labels (if (or (not-any? #(contains? args %) [:store :query]) (not (every? #(re-matches #"^[^=]+=.*$" %) (:labels args))))
                (print "Invalid label(s) or labels provided without --store or --query") true)
      :fetch (if-not (is-hash-64 value) (print "Invalid id hash") true)
      :query (if (or (some #(contains? args %) [:store :fetch]) (nil? (:labels args))) (print "Cannot use --query together with --store / --fetch or without labels") true)
      :server (if (some #(contains? args %) [:store :fetch :query]) (print "Cannot use --server together with --store / --fetch / --query") true)
      :daemon (if (or (nil? (:server args)) (some #(contains? args %) [:store :fetch]))
                (print "Cannot use --daemon without --server or with --store / --fetch") (do (print "Daemon mode not yet implemented...") false))
      :print-master-key (if-not (reduce #(and %1 (contains? #{:password :print-master-key :config-dir} %2)) true (keys args))
                          (print "Only --password is allowed together with --print-master-key") true))))

(defn- validate-args [args]
  (go-loop [option-keys (keys args)]
    (if-let [key (first option-keys)]
      (when (<! (validate-arg key (args key) args))
        (recur (rest option-keys)))
      args)))

; TODO: some possible future commands:
;   --restore-master-key: takes a key pair as a parameter to regenerate backed up key
;   --change-master-key-password: allow changing the password of the master key seed 
(defn- parse-args [argv]
  (go-loop [args {} current argv]
    (if (not current)
      (<! (validate-args args))
      (case (first current)
        "--config-dir" (recur (assoc args :config-dir (second current)) (nnext current))
        "--password" (recur (assoc args :password (second current)) (nnext current))
        "--store" (recur (assoc args :store (second current)) (nnext current))
        "--label" (recur (assoc args :labels (conj (or (:labels args) []) (second current))) (nnext current))
        "--fetch" (recur (assoc args :fetch (second current)) (nnext current))
        "--query" (recur (assoc args :query (second current)) (next current))
        "--server" (recur (assoc args :server true) (next current))
        "--daemon" (recur (assoc args :daemon true) (next current))
        "--print-master-key" (recur (assoc args :print-master-key true) (next current))
        (do (print "Invalid argument" (first current)) (.exit process 1))))))

(defn- validate-server-config [config]
  (cond
    (not (vector? config)) (print "Invalid root config type: expecting a list")
    (not= (count config) 1) (print "Currently supporting only one server endpoint")
    (not= (map? (first config))) (print "Invalid item in config list: expecting an object")
    (not= ((first config) "type") "http") (print "Currently supporting only HTTP endpoints")
    (not= (set (keys (first config))) #{"type", "server", "port", "pubkey"}) (print "Unexpected set of keys in an endpoint configuration")
    (not (is-hash-64 ((first config) "pubkey"))) (print "Invalid server pubkey format")
    :else true))

(defn- convert-server-config [keyvalues]
   (let [sodium (sod/get-sodium)]
     (reduce #(assoc %1 (keyword (first %2))
                     (if (= (first %2) "pubkey")
                       (.from_hex sodium (second %2))
                       (second %2)))
             {} keyvalues)))

(defn- parse-server-config-file [args]
  (let [config-file-path (get-server-config-file-path (get-config-dir args))
        config-file (.readFileSync fs config-file-path "utf8")
        raw-server-configs (js->clj (.parse js/JSON config-file))]
    (if-not (validate-server-config raw-server-configs)
      (do (print "Invalid configuration; terminating") (.exit process 1))
      (mapv convert-server-config raw-server-configs))))

(defn- get-labels [args]
  (let [label-vec (map #(vec [(second (re-find #"^([^=]+)=" %)) (second (re-find #"=(.+)" %))]) (or (:labels args) []))]
    (reduce #(assoc %1 (first %2) (second %2)) {} label-vec)))

(defn- get-extended-labels [args]
  (-> (get-labels args)
      (assoc "origin" "c3res-cli")
      (assoc "filename" (.basename path (:store args)))))

(defn main [& argv]
  (go
    (<! (sod/init))
    (when-let [args (<! (parse-args argv))]
      (let [master-key (<! (get-or-create-master-key args))
            cache-path (get-shard-cache-path args)
            server-config (first (parse-server-config-file args))
            sodium (sod/get-sodium)]
        (cond
          (:print-master-key args) (print "public: " (.to_hex sodium (:sign-public master-key)) "\nprivate: " (.to_hex sodium (:sign-private master-key)))
          ; CONTINUE HERE:
          ;    - implement mime type support (check npm mime package)
          (:store args) (.readFile fs (:store args) (clj->js {:encoding "utf-8"})
                                   #(when-not %1 (go (print (<! (cache/new-shard cache-path %2 "text/plain" (get-extended-labels args) master-key server-config []))))))
          (:fetch args) (if-let [contents (<! (cache/fetch cache-path (:fetch args) master-key server-config))]
                          (.write (.-stdout process) (.from js/Buffer (:raw contents)))
                          (print "Could not find shard with id" (:fetch args)))
          (:query args) (if-let [data (<! (servercomm/query server-config (get-labels args)))]
                          (shards/pretty-print (shards/read-shard-caps data master-key))
                          (print "Could not perform query"))
          (:server args) (.listen (.createServer http) 3000))))))

(set! *main-cli-fn* main)

