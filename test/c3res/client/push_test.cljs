(ns c3res.client.push-test
  (:require [c3res.client.push :as push]
            [c3res.client.shards :as shards]
            [c3res.client.sodiumhelper :as sod]
            [cljs.core.async :as async :refer [<! chan]]
            [cljs.nodejs :as node]
            [cljs.test :refer-macros [deftest is async]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def os (node/require "os"))
(def path (node/require "path"))

(defn- get-testpath []
  (.join path (.tmpdir os) (str "c3res-push-test" (.-pid (node/require "process")))))

(deftest tests-pushing-shard
  (async done
         (go
           (<! (sod/init))
           (let [should-be-id (push/new-shard "foo" {"label1" "value1"} "text/plain" (chan) {:shard-cache-path (get-testpath)} (shards/generate-keys))]
             (is (some? should-be-id))
             (is (nil? (:error should-be-id))))
           (done))))

(deftest test-invalid-shards
  (async done
         (go
           (<! (sod/init))
           (let [keys (shards/generate-keys)]
             (is (some? (:error (push/new-shard "" {"label" "val"} "type" (chan) {:shard-cache-path (get-testpath)} keys))))
             (is (some? (:error (push/new-shard "foo" {1 "val"} "type" (chan) {:shard-cache-path (get-testpath)} keys))))
             (is (some? (:error (push/new-shard "foo" {"label" 4} "type" (chan) {:shard-cache-path (get-testpath)} keys))))
             (is (some? (:error (push/new-shard "foo" ["label" "val"] "type" (chan) {:shard-cache-path (get-testpath)} keys))))
             (is (some? (:error (push/new-shard "foo" {"label" "val"} "" (chan) {:shard-cache-path (get-testpath)} keys))))
             (is (nil? (:error (push/new-shard "foo" {"label" "val"} "type" (chan) {:shard-cache-path (get-testpath)} keys)))))
           (done))))
