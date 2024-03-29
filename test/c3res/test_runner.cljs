(ns c3res.test-runner
  (:require [cljs.test :as test]
            [cljs.nodejs :as node]
            [c3res.shared.csexp-test]
            [c3res.shared.storage-test]
            [c3res.shared.shards-test]
            [c3res.shared.keystore-test]
            [c3res.client.cache-test]))

(node/enable-util-print!)

(defn run-them-tests []
  ; TODO: automate this somehow...
  (test/run-tests 'c3res.shared.csexp-test)
  (test/run-tests 'c3res.shared.storage-test)
  (test/run-tests 'c3res.shared.shards-test)
  (test/run-tests 'c3res.shared.keystore-test)
  (test/run-tests 'c3res.client.cache-test))

(set! *main-cli-fn* run-them-tests)
