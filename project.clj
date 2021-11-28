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
     [[express "4.17.1"]
      [cookie-parser "1.4.5"]
      [body-parser "1.19.0"]
      [source-map-support "0.5.19"]
      [libsodium-wrappers "0.7.9"]
      [prompt "1.1.0"]]}

  :clean-targets ^{:protect false} ["server_out" "client_out" "test_out"]

  :cljsbuild
    {:builds
      {:server
        {:source-paths ["src/c3res/shared" "src/c3res/server"]
         :compiler {:main c3res.server.core
                    :output-to "server_out/server.js"
                    :output-dir "server_out"
                    :source-map true ;"server_out/src-map"
                    :target :nodejs
                    :optimizations :none
                    :verbose true}}
       :client
        {:source-paths ["src/c3res/shared" "src/c3res/client"]
         :compiler {:main c3res.client.core
                    :output-to "client_out/client.js"
                    :output-dir "client_out"
                    :source-map true ;"client_out/src-map"
                    :target :nodejs
                    :optimizations :none
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
