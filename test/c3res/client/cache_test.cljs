(ns c3res.client.cache-test
  (:require [c3res.client.cache :as cache]
            [c3res.client.shards :as shards]
            [c3res.client.sodiumhelper :as sod]
            [cljs.test :refer-macros [deftest is async]]
            [cljs.core.async :refer [<! chan]]
            [cljs.nodejs :as node])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def fs (node/require "fs"))
(def os (node/require "os"))
(def path (node/require "path"))

(defn- get-testpath []
  (.join path (.tmpdir os) "c3res-cache-test" (str (.-pid (node/require "process")))))

(deftest tests-caching-shard
  (async done
         (go
           (<! (sod/init))
           (let [should-be-id (<! (cache/new-shard "foo" {"label1" "value1"} "text/plain" (chan 1) {:shard-cache-path (get-testpath)} (shards/generate-keys)))]
             (is (some? should-be-id))
             (is (nil? (:error should-be-id))))
           (done))))

(deftest test-invalid-shards
  (async done
         (go
           (<! (sod/init))
           (let [keys (shards/generate-keys)
                 opts {:shard-cache-path (get-testpath)}]
             (is (some? (:error (<! (cache/new-shard "" {"label" "val"} "type" (chan 1) opts keys)))))
             (is (some? (:error (<! (cache/new-shard "foo" {1 "val"} "type" (chan 1) opts keys)))))
             (is (some? (:error (<! (cache/new-shard "foo" {"label" 4} "type" (chan 1) opts keys)))))
             (is (some? (:error (<! (cache/new-shard "foo" ["label" "val"] "type" (chan 1) opts keys)))))
             (is (some? (:error (<! (cache/new-shard "foo" {"label" "val"} "" (chan 1) opts keys)))))
             (is (nil? (:error (<! (cache/new-shard "foo" {"label" "val"} "type" (chan 1) opts keys))))))
           (done))))

(deftest test-nonwriteable-dir
  (async done
         (go
           (<! (sod/init))
           (let [testpath (str (get-testpath) "-ro")]
             (.mkdirSync fs testpath (clj->js {:mode 0400}))
             (is (some? (:error (<! (cache/new-shard "foo" {"label" "val"} "type" (chan 1) {:shard-cache-path testpath} (shards/generate-keys))))))
             (done)))))
