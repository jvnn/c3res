(ns c3res.test-runner
  (:require [cljs.test :as test]
            [cljs.nodejs :as node]
            [c3res.shared.csexp-test]
            [c3res.client.shards-test]
            [c3res.client.storage-test]))

(node/enable-util-print!)

(defn run-them-tests []
  ; TODO: automate this somehow...
  (test/run-tests 'c3res.shared.csexp-test)
  (test/run-tests 'c3res.client.shards-test)
  (test/run-tests 'c3res.client.storage-test))

(set! *main-cli-fn* run-them-tests)
