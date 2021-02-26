(ns c3res.shared.shards-test
  (:require [cljs.nodejs :as node]
            [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as s]
            [c3res.shared.shards :as shards]
            [cljs.core.async :as async :refer [<!]]) 
  (:require-macros [cljs.core.async.macros :refer [go]])) 

(deftest test-simple-create-read
   (go
    (<! (shards/init))
    (let [keypair (shards/generate-keys)]
      (is (= (:raw (shards/read-shard (shards/create-shard "foo" [] "text/plain" keypair) keypair)) "foo")))))
