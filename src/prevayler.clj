(ns prevayler
  (:require
    [taoensso.nippy :as nippy])
  (:import
    [java.io File FileOutputStream FileInputStream DataInputStream DataOutputStream EOFException Closeable IOException]
    [clojure.lang IDeref ExceptionInfo]
    (javax.crypto BadPaddingException)))

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
    (let [[new-state :as state-with-result] (handler @state-atom event)]
      (write-fn event)
      (reset! state-atom new-state)
      state-with-result)))

(defn transient-prevayler! [handler initial-state]
  (let [state-atom (atom initial-state)
        no-write (fn [_ignored])]
    (reify
      Prevayler (handle! [this event]
                  (handle-event! this handler state-atom no-write event))
      IDeref (deref [_] @state-atom)
      Closeable (close [_] (reset! state-atom ::closed)))))

(defn- maybe-encrypted [output-stream output-wrapper]
  (if output-wrapper (output-wrapper output-stream) output-stream))

(defn- maybe-decrypted [input-stream input-wrapper]
  (if input-wrapper (input-wrapper input-stream) input-stream))

(defn prevayler!
  ([handler]
   (prevayler! handler {}))
  ([handler initial-state]
   (prevayler! handler initial-state (File. "journal")))
  ([handler initial-state ^File file]
   (prevayler! handler initial-state file nil nil))
  ([handler initial-state ^File file enc-wrapper dec-wrapper]
   (let [state-atom (atom initial-state)
         backup (produce-backup! file)]

     (when backup
       (with-open [file-in (FileInputStream. backup)
                   decrypted-in (maybe-decrypted file-in dec-wrapper)
                   data-in (DataInputStream. decrypted-in)]
         (restore! handler state-atom data-in)))

     (let [file-out (FileOutputStream. file)
           encrypted-out (maybe-encrypted file-out enc-wrapper)
           data-out (DataOutputStream. encrypted-out)
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
           (.close encrypted-out)
           (.close file-out)
           (reset! state-atom ::closed))
         IDeref
         (deref [_]
           @state-atom))))))