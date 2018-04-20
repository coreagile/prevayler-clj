(ns prevayler
  (:require
    [taoensso.nippy :as nippy])
  (:import
    [java.io File FileOutputStream FileInputStream DataInputStream DataOutputStream EOFException Closeable IOException]
    [clojure.lang IDeref ExceptionInfo]
    (javax.crypto Cipher CipherOutputStream CipherInputStream BadPaddingException)))

(def bad-journal (str "Unable to open backup. Either it was encrypted and you "
                      "attempted to open it without proper ciphers or "
                      "we were interrupted during write"))

(def bad-cipher "Incorrect cipher provided for journal")

(defprotocol Prevayler
  (handle! [_ event]
    "Handle event and return vector containing the new state and event result."))

(defn eval!
  "Handle event and return event result."
  [prevayler event]
  (second (handle! prevayler event)))

(defn step!
  "Handle event and return new state."
  [prevayler event]
  (first (handle! prevayler event)))

(defn backup-file [file]
  (File. (str file ".backup")))

(defn- try-to-restore! [handler state-atom data-in]
  (let [read-value! #(nippy/thaw-from-in! data-in)]
    (reset! state-atom (read-value!))
    (while true                                             ;Ends with EOFException
      (let [[new-state _result] (handler @state-atom (read-value!))]
        (reset! state-atom new-state)))))

(defn- restore! [handler state-atom data-in]
  (try
    (try-to-restore! handler state-atom data-in)
    (catch EOFException _done)
    (catch IOException e
      (if (= BadPaddingException (-> e (.getCause) (.getClass)))
        (throw (ex-info bad-cipher {} e))
        (throw e)))
    (catch ExceptionInfo e
      (throw (ex-info bad-journal {} e)))))

(defn- produce-backup! [file]
  (let [backup (backup-file file)]
    (if (.exists backup)
      backup
      (when (.exists file)
        (assert (.renameTo file backup))
        backup))))

(defn- archive! [^File file]
  (let [new-file (File. (str file "-" (System/currentTimeMillis)))]
    (assert (.renameTo file new-file))))

(defn- write-with-flush! [data-out value]
  (nippy/freeze-to-out! data-out value)
  (.flush data-out))

(defn- handle-event! [this handler state-atom write-fn event]
  (locking this
    (let [state-with-result (handler @state-atom event)]
      (write-fn event)
      (reset! state-atom (first state-with-result))
      state-with-result)))

(defn transient-prevayler! [handler initial-state]
  (let [state-atom (atom initial-state)
        no-write (fn [_ignored])]
    (reify
      Prevayler (handle! [this event]
                  (handle-event! this handler state-atom no-write event))
      IDeref (deref [_] @state-atom)
      Closeable (close [_] (reset! state-atom ::closed)))))

(defn- maybe-encrypted [output-stream cipher]
  (if cipher (CipherOutputStream. output-stream cipher) output-stream))

(defn- maybe-decrypted [input-stream cipher]
  (if cipher (CipherInputStream. input-stream cipher) input-stream))

(defn prevayler!
  ([handler]
   (prevayler! handler {}))
  ([handler initial-state]
   (prevayler! handler initial-state (File. "journal")))
  ([handler initial-state ^File file]
   (prevayler! handler initial-state file nil nil))
  ([handler initial-state ^File file ^Cipher enc-cipher ^Cipher dec-cipher]
   (let [state-atom (atom initial-state)
         backup (produce-backup! file)]

     (when backup
       (with-open [data-in (-> backup
                               (FileInputStream.)
                               (maybe-decrypted dec-cipher)
                               (DataInputStream.))]
         (restore! handler state-atom data-in)))

     (let [data-out (-> file
                        (FileOutputStream.)
                        (maybe-encrypted enc-cipher)
                        (DataOutputStream.))
           write! (partial write-with-flush! data-out)]

       (write! @state-atom)
       (when backup
         (archive! backup))

       (reify
         Prevayler
         (handle! [this event]
           (handle-event! this handler state-atom write! event))
         Closeable
         (close [_]
           (.close data-out)
           (reset! state-atom ::closed))
         IDeref
         (deref [_]
           @state-atom))))))