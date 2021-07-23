(ns c3res.client.core
  (:require [c3res.client.keystore :as keystore]
            [c3res.client.shards :as shards]
            [c3res.client.sodiumhelper :as sod]
            [c3res.client.storage :as storage]
            [clojure.string :as s]
            [cljs.nodejs :as node]
            [cljs.core.async :as async :refer [<! >! chan]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(node/enable-util-print!)
(def prompt (node/require "prompt"))
(set! (.-message prompt) "")
(def http (node/require "http"))

(defn- get-password-interactive-internal [to-confirm c]
  (let [confirmation (some? to-confirm)
        schema (clj->js {"properties" {"password" {"hidden" true "replace" "*" "required" true
                                                   "description" (if confirmation "Confirm password" "Insert password")}}})]
    (.start prompt)
    (.get prompt schema (if confirmation
                          #(if (= (.-password %2) to-confirm)
                             (go (>! c (.-password %2)))
                             (do
                               (print "Inserted strings do not match, please try again.")
                               (get-password-interactive-internal nil c)))
                          #(get-password-interactive-internal (.-password %2) c)))
    c))

(defn- get-password-interactive []
  (get-password-interactive-internal nil (chan)))

(defn- get-or-create-master-key []
  (go
    (if-let [master-key (<! (keystore/get-master-key storage/default-opts get-password-interactive))]
      master-key
      (do
        (print "No master key detected, creating a new one.")
        (<! (keystore/create-master-key storage/default-opts get-password-interactive))))))

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

