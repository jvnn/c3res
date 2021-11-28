(ns c3res.client.keystore-test
  (:require [c3res.client.keystore :as keystore]
            [c3res.client.options :as options]
            [c3res.shared.sodiumhelper :as sod]
            [cljs.test :refer-macros [deftest is async]]
            [cljs.core.async :refer [<!]]
            [cljs.nodejs :as node])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def fs (node/require "fs"))
(def os (node/require "os"))
(def path (node/require "path"))

(defn- get-testfile []
  (.join path (.tmpdir os) "c3res-keystore-test" (str (.-pid (node/require "process"))) "master-key-input"))

(defn- pw-getter []
  (go "foobar"))

(deftest test-create-and-retrieve-master-key
  (let [testfile (get-testfile)
        opts {:master-key-path testfile}
        master-key-input-path (options/get-master-key-input-path opts)
        ]
    (async done
           (go
             (<! (sod/init))
             (let [new-key (<! (keystore/create-master-key master-key-input-path pw-getter))
                   retrieved-key (<! (keystore/get-master-key master-key-input-path pw-getter))]
               (is (= (vec (:public new-key)) (vec (:public retrieved-key))))
               (is (= (vec (:private new-key)) (vec (:private retrieved-key)))))
             (done)))))
