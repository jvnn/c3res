(ns c3res.shared.csexp-test
  (:require [cljs.nodejs :as node]
            [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as s]
            [c3res.shared.csexp :as csexp]))

(defn- buf-to-str [arraybuf]
  (.apply (.-fromCharCode js/String) nil (js/Uint8Array. arraybuf)))

(deftest test-text-enconding
  (is (= (str (csexp/encode '("€"))) "40,51,58,226,130,172,41")))

(deftest test-encode-simple
  (is (= (buf-to-str (csexp/encode '("foo" "bar" "z"))) "(3:foo3:bar1:z)")))

(deftest test-nested-exprs
  (is (= (buf-to-str (csexp/encode '("this" ("is" ("nested!!!!!")) "yo"))) "(4:this(2:is(11:nested!!!!!))2:yo)")))

(deftest test-buf
  (is (= (buf-to-str (csexp/encode (seq ["mybuffer" (js/Uint8Array. (clj->js [35 33 33]))]))) "(8:mybuffer(4:!bin3:#!!))")))

(deftest test-empty
  (is (= (buf-to-str (csexp/encode '())) "()")))

(deftest test-simple-decode
  (is (= (csexp/decode "(3:foo2:ah)") '("foo" "ah"))))

(deftest test-nested-decode
  (is (= (csexp/decode "(1:a(2:bb(3:ccc)4:dddd))") '("a" ("bb" ("ccc") "dddd")))))

(deftest test-parens-in-strings
  (is (= (buf-to-str (csexp/encode '("foo(" ")bar"))) "(4:foo(4:)bar)"))
  (is (= (csexp/decode "(3:ab(2:rr(1:)))") '("ab(" "rr" (")")))))

(deftest test-decode-invalid
  (is (nil? (csexp/decode "2:ab")))
  (is (nil? (csexp/decode "(foo)")))
  (is (nil? (csexp/decode "(1:foo2:ab)")))
  (is (nil? (csexp/decode "(2:ab(1:a)"))))

(deftest test-binary-decode
  (let [result (csexp/decode (map #(.charCodeAt % 0) (seq "(9:something(4:!bin3:#!!))")))
        buffer (second result)]
    (is (instance? js/Uint8Array buffer))
    (is (= (buf-to-str buffer) "#!!"))))

(deftest test-utf8-decode
  (is (= (csexp/decode (js/Uint8Array. [40 51 58 226 130 172 41])) '("€"))))

(deftest test-append
  (let [csexp1 (csexp/encode '("foo" ("bar" "baz")))
        csexp2 (csexp/append csexp1 '("ugh" ("agh")))
        csexp3 (csexp/append csexp1 "notaseq")
        csexp4 (csexp/append csexp1 (js/Uint8Array. [35 33 33]))]
    (is (= (buf-to-str csexp1) "(3:foo(3:bar3:baz))"))
    (is (= (buf-to-str csexp2) "(3:foo(3:bar3:baz)(3:ugh(3:agh)))"))
    (is (= (buf-to-str csexp3) "(3:foo(3:bar3:baz)7:notaseq)"))
    (is (= (buf-to-str csexp4) "(3:foo(3:bar3:baz)(4:!bin3:#!!))"))))

(deftest test-wrap
  (let [csexp1 (csexp/encode '("foo"))
        csexp2 (csexp/wrap csexp1 "bar")
        csexp3 (csexp/wrap csexp2 (js/Uint8Array. [98 97 122]))]
    (is (= (buf-to-str csexp1) "(3:foo)"))
    (is (= (buf-to-str csexp2) "(3:bar(3:foo))"))
    (is (= (buf-to-str csexp3) "((4:!bin3:baz)(3:bar(3:foo)))"))))

(deftest test-decode-single-layer
  (let [test-csexp (csexp/encode '("foo" ("bar" ("1") "(baz") "boom"))
        parts (csexp/decode-single-layer test-csexp)]
    (is (= (count parts) 3))
    (is (= (first parts) "foo"))
    (is (= (buf-to-str (second parts)) "(3:bar(1:1)4:(baz)"))
    (is (= (nth parts 2) "boom"))))

