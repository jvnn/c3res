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
(def http (node/require "http"))

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

(defn- get-or-create-master-key []
  (go
    (if-let [master-key (<! (keystore/get-master-key {} get-password-interactive))]
      master-key
      (do
        (print "No master key detected, creating a new one.")
        (<! (keystore/create-master-key {} create-password-interactive))))))

(defn main [& args]
  (go
    (<! (sod/init))
    (print (str "running c3res client with arguments " (s/join " " args)))
    (go
      (let [master-key (<! (get-or-create-master-key))]
        (print (str "got master key " master-key))))
    (.listen (.createServer http) 3000) ; For now just to have an event loop running...
    ))

(set! *main-cli-fn* main)

