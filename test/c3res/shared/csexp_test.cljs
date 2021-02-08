(ns c3res.shared.csexp-test
  (:require [cljs.nodejs :as node]
            [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as s]
            [c3res.shared.csexp :as csexp]))

(deftest test-csexp-simple
  (let [array (csexp/encode [{:type :int32 :value 3} {:type :int32 :value -333}])]
    (is (= (str (js/Uint8Array. array)) "0,0,0,3,255,255,254,179"))))

(deftest test-strings
  (let [array (csexp/encode [{:type :string :value "foo€bar"}])]
    (is (= (str (js/Uint8Array. array)) "102,111,111,226,130,172,98,97,114"))))

(deftest test-buffer
  (let [array (csexp/encode [{:type :buffer :value (.from js/Uint8Array (clj->js [1 2 3 9 8 7]))}])]
    (is (= (str (js/Uint8Array. array)) "1,2,3,9,8,7"))))

(deftest test-combined
  (let [array (csexp/encode [{:type :buffer :value (.from js/Uint8Array (clj->js [1 2 3]))}
                             {:type :uint32 :value 987654}
                             {:type :string :value "€a"}
                             {:type :int32 :value -1}])]
    (is (= (str (js/Uint8Array. array)) "1,2,3,0,15,18,6,226,130,172,97,255,255,255,255"))))
