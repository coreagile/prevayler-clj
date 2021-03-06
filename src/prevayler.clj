(ns prevayler
  (:require
   [prevayler.locks :refer :all]
   [taoensso.nippy :as nippy])
  (:import
   [clojure.lang IDeref ExceptionInfo]
   [java.io
    Closeable
    DataInputStream
    DataOutputStream
    EOFException
    File
    FileOutputStream
    FileInputStream]))

(def bad-journal "Warning - Corruption at end of prevalence file")

(defprotocol Prevayler
  (handle! [_ event]
    (str "Handle event and return vector containing the new state "
         "and event result.")))

(defn eval!
  "Handle event and return event result."
  [prevayler event]
  (second (handle! prevayler event)))

(defn step!
  "Handle event and return new state."
  [prevayler event]
  (first (handle! prevayler event)))

(defn ^String backup-name [file]
  (str file ".backup"))

(defn backup-file [^String file]
  (File. (backup-name file)))

(defn- try-to-restore! [handler state-atom data-in]
  (let [read-value! #(nippy/thaw-from-in! data-in)]
    (reset! state-atom (read-value!))

    ;Ends with EOFException
    (while true
      (let [[new-state _result] (handler @state-atom (read-value!))]
        (reset! state-atom new-state)))))

(defn restore! [handler state-atom data-in]
  (try
    (try-to-restore! handler state-atom data-in)
    (catch EOFException _done)
    (catch ExceptionInfo e
      (throw (ex-info bad-journal {} e)))))

(defn- produce-backup! [file]
  (let [backup (backup-file file)]
    (if (.exists backup)
      backup
      (when (.exists file)
        (assert (.renameTo file backup))
        backup))))

(defn ^String archive-name [file]
  (str file "-" (System/currentTimeMillis)))

(defn- archive! [^File file]
  (let [new-file (File. (archive-name file))]
    (assert (.renameTo file new-file))))

(defn write-with-flush! [data-out value]
  (nippy/freeze-to-out! data-out value)
  (.flush data-out))

(defn handle-event! [handler state-atom write-fn event]
  (with-global-access-lock
    (let [[new-state :as state-with-result] (handler @state-atom event)]
      (write-fn event)
      (reset! state-atom new-state)
      state-with-result)))

(defn transient-prevayler! [handler initial-state]
  (let [state-atom (atom initial-state)
        no-write (fn [_ignored])]
    (reify
      Prevayler (handle! [_ event]
                  (handle-event! handler state-atom no-write event))
      IDeref (deref [_] @state-atom)
      Closeable (close [_] (reset! state-atom ::closed)))))

(defn- maybe-output-wrapped [output-stream output-wrapper]
  (if output-wrapper (output-wrapper output-stream) output-stream))

(defn- maybe-input-wrapped [input-stream input-wrapper]
  (if input-wrapper (input-wrapper input-stream) input-stream))

(defn try-to-close [thing]
  (try (.close thing) (catch Throwable t (.printStackTrace t))))

(defn prevayler!
  ([handler]
   (prevayler! handler {}))
  ([handler initial-state]
   (prevayler! handler initial-state (File. "journal")))
  ([handler initial-state ^File file]
   (prevayler! handler initial-state file nil))
  ([handler initial-state ^File file out-wrapper]
   (prevayler! handler initial-state file out-wrapper nil))
  ([handler initial-state ^File file out-wrapper in-wrapper]
   (let [state-atom (atom initial-state)
         backup (produce-backup! file)]

     (when backup
       (with-open [file-in (FileInputStream. backup)
                   wrapped-in (maybe-input-wrapped file-in in-wrapper)
                   data-in (DataInputStream. wrapped-in)]
         (restore! handler state-atom data-in)))

     (let [file-out (FileOutputStream. file)
           wrapped-out (maybe-output-wrapped file-out out-wrapper)
           data-out (DataOutputStream. wrapped-out)
           write! (partial write-with-flush! data-out)]

       (write! @state-atom)
       (when backup (archive! backup))

       (reify
         Prevayler
         (handle! [_ event] (handle-event! handler state-atom write! event))

         Closeable
         (close [_]
           (try-to-close data-out)
           (try-to-close wrapped-out)
           (try-to-close file-out)
           (reset! state-atom ::closed))

         IDeref
         (deref [_] @state-atom))))))

(comment
 (defn- handler [state event]
   (when (= "boom" event) (throw (RuntimeException.)))
   (if (= "reset" event)
     ["" ""]
     [(str state event)
      (str "+" event)]))

 (def fname "journal")

 (require '[prevayler.examples.s3 :as s3])
 (require '[prevayler.examples.crypto :as crypto])

 ;; Application-managed encryption-at-rest and backups

 (def encryption-key (crypto/aes-key))

 (def p
   (prevayler!
    handler
    ""
    (File. fname)
    (fn [s]
      (-> s
          ((s3/backup-wrapper
            (System/getenv "PREVAYLER_BUCKET")
            :key fname
            :debug? true))
          ((crypto/aes-cipher-wrapper crypto/encrypt-mode encryption-key))))
    (crypto/aes-cipher-wrapper cipher/decrypt-mode encryption-key)))

 ;; This should break
 (def other-key (crypto/aes-key))
 (prevayler! handler
             ""
             (File. fname)
             (crypto/aes-cipher-wrapper crypto/encrypt-mode other-key)
             (crypto/aes-cipher-wrapper cipher/decrypt-mode other-key))

 ;; Without encryption, with backups

 (def p
   (prevayler! handler
               ""
               (File. fname)
               (s3/backup-wrapper
                (System/getenv "PREVAYLER_BUCKET")
                :key fname
                :debug? true)))

 (def futures (->> (range 100)
                   (map
                    (fn [n]
                      (future
                       (Thread/sleep (+ 1000 (* (rand-int 10) n)))
                       (handle! p (str "\n" (java.util.Date.) " -- " n))
                       "success")))
                   doall))

 (time (handle! p "\n---"))

 (handle! p "reset")

 (handle! p "boom")

 (println @p)

 (.close p)

 *e)