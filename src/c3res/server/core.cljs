(ns c3res.server.core
  (:require [cljs.nodejs :as node]
            [cljs.core.async :as async :refer [<! chan]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(node/enable-util-print!)

(def express (node/require "express"))

; Some thoughts:
;  - POST or PUT: all stores should be idempotent (also labels should be stored in
;    the shard to allow reconstruction so nothing in a shard should change), so probably PUT
;  - metadata like public labels should also be signed, so we'll need multiple layers
;    of csexps where the topmost is our envelope and the shard itself is inside it

(defn -main [& args]
  (let [app (express)]
    (.get app "/" (fn [req res] (.send res "Hello C3RES!")))
    (.listen app 3001)))

(set! *main-cli-fn* -main)

