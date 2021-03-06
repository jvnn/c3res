(defproject c3res "0.0.1"
  :description "C3RES - Content-addressed Cryptographically-secured Capability-based Resource Store"
  :url "c3res.org"
  :license {:name "EUPL-1.2"
            :url "https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12"}
  :dependencies [[org.clojure/clojure "1.10.2"]
                 [org.clojure/clojurescript "1.10.773"]
                 [org.clojure/core.async "1.3.610"]]

  :plugins [[lein-npm "0.6.2"]
            [lein-cljsbuild "1.1.8"]]
  :npm
    {:dependencies
     [[source-map-support "0.5.19"]
      [libsodium-wrappers "0.7.9"]]}

  :clean-targets ^{:protect false} ["server_out" "client_out" "test_out"]

  :cljsbuild
    {:builds
      {:server
        {:source-paths ["src"]
         :compiler {:main c3res.server.core
                    :output-to "server_out/server.js"
                    :output-dir "server_out"
                    :source-map "server_out/src-map"
                    :target :nodejs
                    :optimizations :simple
                    :verbose true}}
       :client
        {:source-paths ["src"]
         :compiler {:main c3res.client.core
                    :output-to "client_out/client.js"
                    :output-dir "client_out"
                    :source-map "client_out/src-map"
                    :target :nodejs
                    :optimizations :simple
                    :verbose true}}
       :test
        {:source-paths ["src" "test"]
         :compiler {:main c3res.test-runner
                    :output-to "test_out/test.js"
                    :output-dir "test_out"
                    :source-map true
                    :target :nodejs
                    :optimizations :none
                    :verbose true}}}})
