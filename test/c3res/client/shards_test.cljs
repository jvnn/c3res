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
            with-metadata (shards/create-with-metadata "foo" "text/plain" {"label1" "value1" "label2" "value2"} keypair)
            metadata (shards/read-shard (:data (:metadata with-metadata)) keypair)
            contents (shards/read-shard (:data (:shard with-metadata)) keypair)]
        (is (= (:raw contents) "foo"))
        (is (= (:labels (:metadata metadata)) {"label1" "value1" "label2" "value2"}))
        (done)))))
