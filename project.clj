(defproject coreagile/prevayler-clj "3.0.16"
  :description "Simple, fast, ACID persistence in Clojure."
  :url "https://github.com/klauswuestefeld/prevayler-clj"
  :license {:name "BSD"
            :url "http://opensource.org/licenses/BSD-3-Clause"}
  :dependencies [[org.clojure/clojure "1.12.6" :scope "provided"]
                 [com.taoensso/nippy "3.9.0"]]
  :profiles {:dev {:dependencies [[midje "1.9.9"]]
                   :plugins [[lein-ancient "0.6.15"]
                             [lein-midje "3.2.2"]]}}

  :repositories [["clojars" { :sign-releases false}]])
