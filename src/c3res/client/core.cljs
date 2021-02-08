(ns c3res.client.core
  (:require [c3res.shared.shards :as shards]
            [cljs.nodejs :as node]
            [cljs.core.async :as async :refer [<!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(node/enable-util-print!)

(defn main []
  (go
    (<! (shards/init))
    (println "foo")))

(set! *main-cli-fn* main)

