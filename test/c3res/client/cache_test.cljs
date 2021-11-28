(ns c3res.client.cache-test
  (:require [c3res.client.cache :as cache]
            [c3res.shared.shards :as shards]
            [c3res.shared.sodiumhelper :as sod]
            [cljs.test :refer-macros [deftest is async]]
            [cljs.core.async :refer [<! chan]]
            [cljs.nodejs :as node])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def fs (node/require "fs"))
(def os (node/require "os"))
(def path (node/require "path"))

(defn- get-testpath []
  (.join path (.tmpdir os) "c3res-cache-test" (str (.-pid (node/require "process")))))

(deftest test-caching-shard
  (async done
         (go
           (<! (sod/init))
           (let [should-be-id (<! (cache/new-shard (get-testpath) "foo" "text/plain" {"label1" "value1"} (chan 1) (shards/generate-keys)))]
             (is (some? should-be-id))
             (is (nil? (:error should-be-id))))
           (done))))

(deftest test-invalid-shards
  (async done
         (go
           (<! (sod/init))
           (let [keys (shards/generate-keys)
                 testpath (get-testpath)]
             (is (some? (:error (<! (cache/new-shard testpath "" "type" {"label" "val"} (chan 1) keys)))))
             (is (some? (:error (<! (cache/new-shard testpath "foo" "type" {1 "val"} (chan 1) keys)))))
             (is (some? (:error (<! (cache/new-shard testpath "foo" "type" {"label" 4} (chan 1) keys)))))
             (is (some? (:error (<! (cache/new-shard testpath "foo" "type" ["label" "val"] (chan 1) keys)))))
             (is (some? (:error (<! (cache/new-shard testpath "foo" "" {"label" "val"} (chan 1) keys)))))
             (is (nil? (:error (<! (cache/new-shard testpath "foo" "type" {"label" "val"} (chan 1) keys))))))
           (done))))

(deftest test-nonwriteable-dir
  (async done
         (go
           (<! (sod/init))
           (let [testpath (str (get-testpath) "-ro")]
             (.mkdirSync fs testpath (clj->js {:mode 0400}))
             (is (some? (:error (<! (cache/new-shard testpath "foo" "type" {"label" "val"} (chan 1) (shards/generate-keys))))))
             (done)))))

(deftest test-cache-and-fetch
  (async done
         (go
           (<! (sod/init))
           (let [keys (shards/generate-keys)
                 id (:shard-id (<! (cache/new-shard (get-testpath) "foo" "text/plain" {"label1" "value1"} (chan 1) keys)))
                 shard (<! (cache/fetch (get-testpath) id keys))]
             (is (= (:raw shard) "foo")))
           (done))))

