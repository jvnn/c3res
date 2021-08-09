(ns c3res.client.core
  (:require [c3res.client.keystore :as keystore]
            [c3res.client.cache :as cache]
            [c3res.client.sodiumhelper :as sod]
            [c3res.client.storage :as storage]
            [clojure.string :as s]
            [cljs.nodejs :as node]
            [cljs.core.async :as async :refer [<! >! chan close!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

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

(defn- validate-args [args]
  (doseq [key (keys args)]
    (let [value (args key)]
      (case key
        :password (when (s/blank? value) (print "Invalid or missing password") (.exit process 1))
        :store (when (not (.accessSync fs value)) (print "Cannot find or access file to store") (.exit process 1))
        :fetch (do (print "Fetching not yet implemented...") (.exit process 0))
        :server (when (some #(contains? args %) [:store :fetch]) (print "Cannot use --server together with --store / --fetch") (.exit process 1))
        :daemon (when (or (not (:server args)) (some #(contains? args %) [:store :fetch])) (print "Cannot use --daemon without --server or with --store / --fetch") (.exit process 1)))))
  args)

(defn- parse-args [argv]
  (loop [args {} current argv]
    (if (not current)
      (validate-args args)
      (case (first current)
        "--password" (recur (assoc args :password (second current)) (nnext current))
        "--store" (recur (assoc args :store (second current)) (nnext current))
        "--fetch" (recur (assoc args :fetch (second current)) (nnext current))
        "--server" (recur (assoc args :server true) (next current))
        "--daemon" (recur (assoc args :daemon true) (next current))
        nil))))

(defn main [& argv]
  (go
    (<! (sod/init))
    (print (str "running c3res client with arguments " (s/join " " argv)))
    (go
      (let [args (parse-args argv)
            master-key (<! (get-or-create-master-key (:password args)))]
        (print (str "got master key " master-key))))
    (.listen (.createServer http) 3000) ; For now just to have an event loop running...
    ))

(set! *main-cli-fn* main)

