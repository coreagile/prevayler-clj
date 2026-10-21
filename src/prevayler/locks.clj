(ns prevayler.locks
  (:import (java.util.concurrent TimeUnit)
           (java.util.concurrent.locks ReentrantLock)))

(def global-access-lock (delay (ReentrantLock.)))

(defn -with-global-access-lock [f]
  (let [^ReentrantLock lock @global-access-lock]
    (try
      (if (or (.tryLock lock)
              (.tryLock lock 5 TimeUnit/SECONDS))
        (f)
        (throw (ex-info "Failed to acquire access lock" {})))
      (finally
        (.unlock lock)))))

(defmacro with-global-access-lock [& body]
  `(-with-global-access-lock (fn [] ~@body)))
