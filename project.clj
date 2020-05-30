(defproject coreagile/prevayler-clj "3.0.9-SNAPSHOT"
  :description "Simple, fast, ACID persistence in Clojure."
  :url "https://github.com/klauswuestefeld/prevayler-clj"
  :license {:name "BSD"
            :url "http://opensource.org/licenses/BSD-3-Clause"}
  :dependencies [[org.clojure/clojure "1.10.1" :scope "provided"]
                 [com.taoensso/nippy "2.14.0"]

                 ;; Download these yourself if you want to use
                 ;; S3 backup features
                 [software.amazon.awssdk/s3 "2.13.23"
                  :scope "provided"]
                 [commons-io/commons-io "2.7"
                  :scope "provided"]]
  :profiles {:dev {:dependencies [[midje "1.9.9"]]
                   :plugins [[lein-ancient "0.6.15"]
                             [lein-midje "3.2.2"]]}}

  :repositories [["clojars" { :sign-releases false}]])
