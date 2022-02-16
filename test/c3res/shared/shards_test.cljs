(ns c3res.shared.shards-test
  (:require [cljs.nodejs :as node]
            [cljs.test :refer-macros [deftest is async]]
            [clojure.string :as s]
            [c3res.shared.shards :as shards]
            [c3res.shared.sodiumhelper :as sod]
            [cljs.core.async :as async :refer [<!]]) 
  (:require-macros [cljs.core.async.macros :refer [go]])) 

(deftest test-simple-create-read
  (async done
    (go
      (<! (sod/init))
      (let [keypair (shards/generate-keys)
            with-metadata (shards/create-with-metadata "foo" "text/plain" {"label1" "value1" "label2" "value2"} keypair keypair nil [])
            metadata (shards/read-shard (:data (:metadata with-metadata)) keypair)
            contents (shards/read-shard (:data (:shard with-metadata)) keypair)]
        (is (nil? (:error contents)))
        (is (nil? (:error metadata)))
        (is (= (:raw contents) "foo"))
        (is (= (:labels (:metadata metadata)) {"label1" "value1" "label2" "value2"}))
        (done)))))

(deftest test-create-read-with-caps
  (async done
    (go
      (<! (sod/init))
      (let [author-keypair (shards/generate-keys)
            caps-keypair (shards/generate-keys)
            other-keypair (shards/generate-keys)
            yetanother-keypair (shards/generate-keys)
            server-keypair (shards/generate-keys)
            with-metadata (shards/create-with-metadata "foo" "text/plain" {"label" "value"} author-keypair author-keypair (:enc-public server-keypair)
                                                       [(:enc-public other-keypair) (:enc-public caps-keypair)])
            metadata (shards/read-shard-caps (:data (:metadata with-metadata)) server-keypair)
            empty-contents (shards/read-shard-caps (:data (:shard with-metadata)) yetanother-keypair)
            contents (shards/read-shard-caps (:data (:shard with-metadata)) caps-keypair)] 
        (is (some? (:error empty-contents)))
        (is (nil? (:error contents)))
        (is (nil? (:error metadata)))
        (is (= (:raw contents) "foo"))
        (is (= (:labels (:metadata metadata)) {"label" "value"}))
        (done)))))
