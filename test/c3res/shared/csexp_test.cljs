(ns c3res.shared.csexp-test
  (:require [cljs.nodejs :as node]
            [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as s]
            [c3res.shared.csexp :as csexp]))

(deftest test-strings
  (let [array (csexp/serialize [{:type :string :value "foo€bar"}])]
    (is (= (str (js/Uint8Array. array)) "102,111,111,226,130,172,98,97,114"))))

(deftest test-buffer
  (let [array (csexp/serialize [{:type :buffer :value (.from js/Uint8Array (clj->js [1 2 3 9 8 7]))}])]
    (is (= (str (js/Uint8Array. array)) "1,2,3,9,8,7"))))

(deftest test-combined
  (let [array (csexp/serialize [{:type :buffer :value (.from js/Uint8Array (clj->js [1 2 3]))}
                                {:type :string :value "€a"}])]
    (is (= (str (js/Uint8Array. array)) "1,2,3,226,130,172,97"))))

(defn- buf-to-str [arraybuf]
  (.apply (.-fromCharCode js/String) nil (js/Uint8Array. arraybuf)))

(deftest test-encode-simple
  (is (= (buf-to-str (csexp/encode '("foo" "bar" "z"))) "(3:foo3:bar1:z)")))

(deftest test-nested-exprs
  (is (= (buf-to-str (csexp/encode '("this" ("is" ("nested!!!!!"))))) "(4:this(2:is(11:nested!!!!!)))")))

(deftest test-buf
  (is (= (buf-to-str (csexp/encode (seq ["buffer:" (js/Uint8Array. (clj->js [35 33 33]))]))) "(7:buffer:3:#!!)")))

(deftest test-empty
  (is (= (buf-to-str (csexp/encode '())) "()")))

(deftest test-simple-decode
  (is (= (csexp/decode "(3:foo2:ah)") '("foo" "ah")))
  )
