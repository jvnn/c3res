(require
  '[cljs.repl :as repl]
  '[cljs.repl.node :as noderepl]
  '[cljs.build.api :as build])

(build/build "src"
             {:main 'c3res.client.core
              :output-to "client_out/client.js"
              :output-dir "client_out"
              :target :nodejs
              :optimizations :none
              :language-in :ecmascript6-strict})

(repl/repl (noderepl/repl-env)
           :watch "src"
           :output-dir "client_out"
           :optimizations :none
           :target :nodejs
           :language-in :ecmascript6-strict
           :cache-analysis true
           :source-map true)
