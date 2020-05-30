(ns prevayler.locks
  (:import (java.util.concurrent TimeUnit)
           (java.util.concurrent.locks ReentrantLock)))

(def global-access-lock (ReentrantLock.))

(defmacro with-global-access-lock [& body]
  `(try
     (if (or (.tryLock global-access-lock)
             (.tryLock global-access-lock 5 TimeUnit/SECONDS))
       (do ~@body)
       (throw (ex-info "Failed to acquire access lock" {})))
     (finally
       (.unlock global-access-lock))))
