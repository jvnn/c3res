(ns c3res.client.storage-test
  (:require [c3res.client.storage :as storage]
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
  (is (= (storage/join-opts {:master-key-path "/foo/bar" :whateva "unsupported"}) {:master-key-path "/foo/bar" :shard-cache-path (storage/default-opts :shard-cache-path)}))
  (is (= (storage/join-opts {}) storage/default-opts)))

(deftest test-store-get-master-key-input
  (let [testfile (get-testfile)
        opts {:master-key-path testfile}]
    (async done
           (go
             (<! (storage/store-master-key-input opts "testcontents"))
             (is (= "testcontents" (.toString (<! (storage/get-master-key-input opts)))))
             (done)))))
