(ns c3res.client.storage-test
  (:require [c3res.client.storage :as storage]
            [cljs.test :refer-macros [deftest is async]]
            [cljs.core.async :refer [<!]]
            [cljs.nodejs :as node])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def fs (node/require "fs"))
(def os (node/require "os"))
(def path (node/require "path"))

(deftest test-joining-options
  (is (= (storage/join-opts {:master-key-path "/foo/bar" :whateva "unsupported"}) {:master-key-path "/foo/bar"}))
  (is (= (storage/join-opts {}) storage/default-opts)))

(deftest test-getting-master-key-input
  (let [testfile (.join path (.tmpdir os) (str "c3res-storage-test-" (.-pid (node/require "process")) "-master-key-input"))]
    (.writeFileSync fs testfile "testcontents")
    (async done
           (go
             (is (= "testcontents" (.toString (<! (storage/get-master-key-input {:master-key-path testfile})))))
             (done)))))
