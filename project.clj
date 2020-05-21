(defproject coreagile/prevayler-clj "3.0.6-SNAPSHOT"
  :description "Simple, fast, ACID persistence in Clojure."
  :url "https://github.com/klauswuestefeld/prevayler-clj"
  :license {:name "BSD"
            :url "http://opensource.org/licenses/BSD-3-Clause"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [com.taoensso/nippy "2.14.0"]

                 [software.amazon.awssdk/s3 "2.1.0"
                  :scope "provided"]]
  :profiles {:dev {:dependencies [[midje "1.9.1"]]
                   :plugins [[lein-midje "3.2.1"]]}}

  :repositories [["clojars" { :sign-releases false}]])
