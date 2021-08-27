(ns c3res.client.storage-test
  (:require [c3res.client.options :as options]
            [c3res.client.storage :as storage]
            [cljs.test :refer-macros [deftest is async]]
            [cljs.core.async :refer [<!]]
            [cljs.nodejs :as node])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def fs (node/require "fs"))
(def os (node/require "os"))
(def path (node/require "path"))

(defn- get-testfile []
  (.join path (.tmpdir os) "c3res-storage-test" (str (.-pid (node/require "process"))) "master-key-input"))

(deftest test-joining-options
  (is (= (options/join-opts {:master-key-path "/foo/bar" :whateva "unsupported"}) {:master-key-path "/foo/bar" :shard-cache-path (options/default-opts :shard-cache-path)}))
  (is (= (options/join-opts {}) options/default-opts)))

(deftest test-store-get-file
  (let [testfile (get-testfile)]
    (async done
           (go
             (<! (storage/store-file testfile "testcontents"))
             (is (= "testcontents" (.toString (<! (storage/get-file testfile)))))
             (done)))))
