(ns c3res.client.keystore-test
  (:require [c3res.client.keystore :as keystore]
            [c3res.client.sodiumhelper :as sod]
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

(defn- key-seed-getter []
  (go "this is a very long string that should be fine for a key seed as long as its at least 50 chars long"))

(defn- empty-key-seed-getter []
  (go ""))

(deftest test-create-and-retrieve-master-key
  (let [testfile (get-testfile)
        opts {:master-key-path testfile}]
    (async done
           (go
             (<! (sod/init))
             ; try with a given seed string...
             (let [new-key (<! (keystore/create-master-key opts pw-getter key-seed-getter))
                   retrieved-key (<! (keystore/get-master-key opts pw-getter))]
               (is (= (vec (:public new-key)) (vec (:public retrieved-key))))
               (is (= (vec (:private new-key)) (vec (:private retrieved-key)))))

             ; ... and then with a random generated seed
             (let [new-key (<! (keystore/create-master-key opts pw-getter empty-key-seed-getter))
                   retrieved-key (<! (keystore/get-master-key opts pw-getter))]
               (is (= (vec (:public new-key)) (vec (:public retrieved-key))))
               (is (= (vec (:private new-key)) (vec (:private retrieved-key))))) 
             (done)))))
