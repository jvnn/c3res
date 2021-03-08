(ns c3res.client.shards-test
  (:require [cljs.nodejs :as node]
            [cljs.test :refer-macros [deftest is async]]
            [clojure.string :as s]
            [c3res.client.shards :as shards]
            [cljs.core.async :as async :refer [<!]]) 
  (:require-macros [cljs.core.async.macros :refer [go]])) 

(deftest test-simple-create-read
  (async done
    (go
      (<! (shards/init))
      (let [keypair (shards/generate-keys)]
        (is (= (:raw (shards/read-shard (:shard (shards/create-shard "foo" [] "text/plain" keypair)) keypair)) "foo"))
        (done)))))
