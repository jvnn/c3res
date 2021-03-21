(ns c3res.client.core
  (:require [c3res.client.shards :as shards]
            [c3res.client.sodiumhelper :as sod]
            [clojure.string :as s]
            [cljs.nodejs :as node]
            [cljs.core.async :as async :refer [<!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(node/enable-util-print!)

(defn main [& args]
  (go
    (<! (sod/init))
    (print (str "running c3res client with arguments " (s/join " " args)))))

(set! *main-cli-fn* main)

