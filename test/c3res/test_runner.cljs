(ns c3res.test-runner
  (:require [cljs.test :as test]
            [cljs.nodejs :as node]
            [c3res.shared.csexp-test]
            [c3res.client.shards-test]))

(node/enable-util-print!)

(defn run-them-tests []
  (test/run-tests 'c3res.shared.csexp-test)
  (test/run-tests 'c3res.client.shards-test))

(set! *main-cli-fn* run-them-tests)
