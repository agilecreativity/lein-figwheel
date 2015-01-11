(ns figwheel-sidecar.auto-builder
  (:require
   [clojure.pprint :as p]
   [figwheel-sidecar.core :as fig]
   [figwheel-sidecar.repl :as fig-repl]
   [cljs.analyzer]
   [cljs.env]
   [clj-stacktrace.repl]
   [clojurescript-build.core :as cbuild]
   [clojurescript-build.auto :as auto]
   [clojure.java.io :as io]))

(defn check-changes [figwheel-server build]
  (let [{:keys [additional-changed-ns build-options id old-mtimes new-mtimes]} build]
    (fig/check-for-changes (merge figwheel-server
                                  (if id {:build-id id} {})
                                  (select-keys build-options [:output-dir :output-to]))
                           old-mtimes
                           new-mtimes
                           additional-changed-ns)))

(defn handle-exceptions [figwheel-server {:keys [build-options exception]}]
  (println (auto/red (str "Compiling \"" (:output-to build-options) "\" failed.")))
  (clj-stacktrace.repl/pst+ exception)
  (fig/compile-error-occured figwheel-server exception))

(defn builder [figwheel-server]
  (-> cbuild/build-source-paths*
    (auto/warning (auto/warning-message-handler
                   (partial fig/compile-warning-occured figwheel-server)))
    auto/time-build
    (auto/after auto/compile-success)    
    (auto/after (partial check-changes figwheel-server))
    (auto/error (partial handle-exceptions figwheel-server))
    (auto/before auto/compile-start)))

(defn autobuild* [{:keys [builds figwheel-server]}]
  (auto/autobuild*
   {:builds  builds
    :builder (builder figwheel-server)
    :each-iteration-hook (fn [_] (fig/check-for-css-changes figwheel-server))}))

(defn mkdirs [fpath]
  (let [f (io/file fpath)]
    (when-let [dir (.getParentFile f)] (.mkdirs dir))))

(defn autobuild-repl [{:keys [builds figwheel-server] :as opts}]
  (let [builds' (mapv auto/prep-build
                      builds)
        logfile-path (or (:server-logfile figwheel-server) "figwheel_server.log")
        _ (mkdirs logfile-path)
        log-writer (io/writer logfile-path :append true)]
    (println "Server output being sent to logfile:" logfile-path "\n")
    (binding [*out* log-writer
              *err* log-writer]
      ;; blocking build to ensure code exists before repl starts
      ((builder figwheel-server) (first builds'))
      (autobuild* {:builds builds'
                   :figwheel-server figwheel-server }))
    (if (:id (first builds'))
      (println "Launching ClojureScript REPL for build:" (:id (first builds')))
      (println "Launching ClojureScript REPL"))
    (println "Prompt will show when figwheel connects to your application")
    (fig-repl/repl (first builds') figwheel-server)))

(defn autobuild [src-dirs build-options figwheel-options]
  (autobuild* {:builds [{:source-paths src-dirs
                         :build-options build-options}]
               :figwheel-server (fig/start-server figwheel-options)}))

(comment
  
  (def builds [{ :id "example"
                 :source-paths ["src" "../support/src"]
                 :build-options { :output-to "resources/public/js/compiled/example.js"
                                  :output-dir "resources/public/js/compiled/out"
                                  :source-map true
                                  :cache-analysis true
                                  ;; :reload-non-macro-clj-files false
                                  :optimizations :none}}])

  (def env-builds (map (fn [b] (assoc b :compiler-env
                                      (cljs.env/default-compiler-env
                                        (:compiler b))))
                        builds))
  
  (def figwheel-server (fig/start-server))

  (fig/stop-server figwheel-server)
  
  (def bb (autobuild* {:builds env-builds
                             :figwheel-server figwheel-server}))

  (auto/stop-autobuild! bb)

  (fig-repl/eval-js figwheel-server "1 + 1")

  (def build-options (:build-options (first builds)))
  
  (cljs.repl/repl (repl-env figwheel-server) )
)
