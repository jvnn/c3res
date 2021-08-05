(ns c3res.client.shards-test
  (:require [cljs.nodejs :as node]
            [cljs.test :refer-macros [deftest is async]]
            [clojure.string :as s]
            [c3res.client.shards :as shards]
            [c3res.client.sodiumhelper :as sod]
            [cljs.core.async :as async :refer [<!]]) 
  (:require-macros [cljs.core.async.macros :refer [go]])) 

(deftest test-simple-create-read
  (async done
    (go
      (<! (sod/init))
      (let [keypair (shards/generate-keys)
            shard (:shard (shards/create-shard "foo" {"label1" "value1" "label2" "value2"} "text/plain" keypair))
            contents (shards/read-shard shard keypair)]
        (is (= (:raw contents) "foo"))
        (is (= (:labels contents) '("map" ("label1" "value1") ("label2" "value2"))))
        (done)))))
