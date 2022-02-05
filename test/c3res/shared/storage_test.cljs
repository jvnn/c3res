(ns c3res.shared.storage-test
  (:require [c3res.shared.storage :as storage]
            [cljs.test :refer-macros [deftest is async]]
            [cljs.core.async :refer [<!]]
            [cljs.nodejs :as node])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def fs (node/require "fs"))
(def os (node/require "os"))
(def path (node/require "path"))

(defn- get-testfile []
  (.join path (.tmpdir os) "c3res-storage-test" (str (.-pid (node/require "process"))) "master-key-input"))

(deftest test-store-get-file
  (let [testfile (get-testfile)]
    (async done
           (go
             (<! (storage/store-file testfile "testcontents"))
             (is (= "testcontents" (.toString (<! (storage/get-file testfile)))))
             (done)))))
