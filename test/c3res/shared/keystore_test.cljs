(ns c3res.shared.keystore-test
  (:require [c3res.shared.keystore :as keystore]
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

(defn- wrong-pw-getter []
  (go "barfoo"))

(deftest test-create-and-retrieve-master-key
  (let [testfile (get-testfile)]
    (async done
           (go
             (<! (sod/init))
             (let [new-key (<! (keystore/create-master-key testfile pw-getter))
                   retrieved-key (<! (keystore/get-master-key testfile pw-getter))]
               (is (= (vec (:public new-key)) (vec (:public retrieved-key))))
               (is (= (vec (:private new-key)) (vec (:private retrieved-key)))))
             (done)))))

(deftest test-retrieve-with-wrong-password-fails
  (let [testfile (get-testfile)]
    (async done
           (go
             (<! (sod/init))
             (let [new-key (<! (keystore/create-master-key testfile pw-getter))
                   retrieved-key (<! (keystore/get-master-key testfile wrong-pw-getter))]
               (is (= (:error retrieved-key) :password)))
             (done)))))
