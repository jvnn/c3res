(ns c3res.client.core
  (:require [c3res.client.keystore :as keystore]
            [c3res.client.cache :as cache]
            [c3res.client.sodiumhelper :as sod]
            [c3res.client.storage :as storage]
            [clojure.string :as s]
            [cljs.nodejs :as node]
            [cljs.core.async :as async :refer [<! >! put! chan close!]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(node/enable-util-print!)
(def prompt (node/require "prompt"))
(set! (.-message prompt) "")

(def fs (node/require "fs"))
(def http (node/require "http"))
(def process (node/require "process"))

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
                               (get-password-interactive-internal false c)))
                          #(go (>! c (.-password %2)))))
    c))

(defn- get-password-interactive []
  (get-password-interactive-internal false (chan)))

(defn- create-password-interactive []
  (get-password-interactive-internal true (chan)))

(defn- get-or-create-master-key [password-from-args]
  (let [pw-getter (if password-from-args #(go password-from-args) get-password-interactive)]
    (go
      (if-let [master-key (<! (keystore/get-master-key {} pw-getter))]
        master-key
        (do
          (print "No master key detected, creating a new one.")
          (<! (keystore/create-master-key {} create-password-interactive)))))))

(defn- access [path]
  (let [c (chan)]
    (.access fs path #(if %1 (put! c false) (put! c true)))
    c))

(defn- validate-arg [key value args]
  (go
    (case key
      :password (if (s/blank? value) (print "Invalid or missing password") true)
      :store (if (not (<! (access value))) (print (str "Cannot find or access file '" value "' to store")) true)
      :fetch (do (print "Fetching not yet implemented...") false)
      :server (if (some #(contains? args %) [:store :fetch]) (print "Cannot use --server together with --store / --fetch") true)
      :daemon (if (or (not (:server args)) (some #(contains? args %) [:store :fetch]))
                (print "Cannot use --daemon without --server or with --store / --fetch") (do (print "Daemon mode not yet implemented...") false)))))

(defn- validate-args [args]
  (go-loop [option-keys (keys args)]
    (if-let [key (first option-keys)]
      (when (<! (validate-arg key (args key) args))
        (recur (rest option-keys)))
      args)))

(defn- parse-args [argv]
  (go-loop [args {} current argv]
    (if (not current)
      (<! (validate-args args))
      (case (first current)
        "--password" (recur (assoc args :password (second current)) (nnext current))
        "--store" (recur (assoc args :store (second current)) (nnext current))
        "--fetch" (recur (assoc args :fetch (second current)) (nnext current))
        "--server" (recur (assoc args :server true) (next current))
        "--daemon" (recur (assoc args :daemon true) (next current))
        (do (print "Invalid argument" (first current)) (.exit process 1))))))

(defn main [& argv]
  (go
    (<! (sod/init))
    (print (str "running c3res client with arguments " (s/join " " argv)))
    (go
      (when-let [args (<! (parse-args argv))]
        (let [master-key (<! (get-or-create-master-key (:password args)))]
          (cond
            ; CONTINUE HERE:
            ;    - implement fetching from cache to check that we store stuff successfully
            ;    - implement adding labels
            ;    - implement (poor man's...?) mime type detection (or check for libraries)
            (:store args) (.readFile fs (:store args) #(when-not %1 (go (<! (cache/new-shard %2 {"test" "label"} "text/plain" (chan 1) {} master-key)))))
            (:server args) (.listen (.createServer http) 3000)))))))

(set! *main-cli-fn* main)

