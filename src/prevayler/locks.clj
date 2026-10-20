(ns prevayler.locks
  (:import (java.util.concurrent TimeUnit)
           (java.util.concurrent.locks ReentrantLock)))

(def global-access-lock (delay (ReentrantLock.)))

(defn -with-global-access-lock [f]
  (try
    (if (or (.tryLock @global-access-lock)
            (.tryLock @global-access-lock 5 TimeUnit/SECONDS))
      (f)
      (throw (ex-info "Failed to acquire access lock" {})))
    (finally
      (.unlock @global-access-lock))))

(defmacro with-global-access-lock [& body]
  `(-with-global-access-lock (fn [] ~@body)))
