(ns c3res.client.httpserver
  (:require [cljs.nodejs :as node]
            [cljs.core.async :as async :refer [<! >! put! chan close!]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(def http (node/require "http"))

(defn create [filename store fetch query]
  nil)
