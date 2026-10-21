(ns prevayler
  (:require
    [prevayler.locks :refer :all]
    [taoensso.nippy :as nippy])
  (:import
    (clojure.lang ExceptionInfo IDeref)
    (java.io
      Closeable
      DataInputStream
      DataOutputStream
      EOFException
      File
      FileInputStream
      FileOutputStream InputStream OutputStream)))

(def bad-journal "Warning - Corruption at end of prevalence file")

(defprotocol Prevayler
  (handle! [_ event]))

(defprotocol OutWrapper
  (wrap-out ^OutputStream [_ ^OutputStream out]))

(defprotocol InWrapper
  (wrap-in ^InputStream [_ ^InputStream out]))

(defprotocol EventWriter
  (write-event [_ e]))

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

(defn backup-file ^File [^String file]
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

(defn ^String archive-name [file]
  (str file "-" (System/currentTimeMillis)))

(defn- archive! [^File file]
  (let [new-file (File. (archive-name file))]
    (assert (.renameTo file new-file))))

(defn write-with-flush! [^OutputStream data-out, value]
  (nippy/freeze-to-out! data-out value)
  (.flush data-out))

(defn handle-event! [handler state-atom prevayler-state event]
  (with-global-access-lock
    (let [[new-state :as state-with-result] (handler @state-atom event)]
      (write-event prevayler-state event)
      (reset! state-atom new-state)
      state-with-result)))

(defn try-to-close [^Closeable thing]
  (try (.close thing) (catch Throwable t (.printStackTrace t))))

(defn- perform-backup! [state-atom, handler, ^File file, in-wrapper]
  (let [^File backup (backup-file file)]
    (when-let
      [^File backup
       (if (.exists backup)
         backup
         (when (.exists file)
           (assert (.renameTo file backup))
           backup))]
      (with-open [^InputStream file-in (FileInputStream. backup)

                  ^InputStream wrapped-in
                  (if in-wrapper (wrap-in in-wrapper file-in) file-in)

                  ^InputStream data-in (DataInputStream. wrapped-in)]
        (restore! handler state-atom data-in))
      (when backup (archive! backup)))))

(defrecord PrevaylerState [state-atom ^OutputStream out]
  EventWriter
  (write-event [_ e] (write-with-flush! out e))

  Closeable
  (close [_]
    (try-to-close out)
    (reset! state-atom ::closed)))

(defn- new-state! [initial-state
                   handler
                   ^File file
                   in-wrapper
                   out-wrapper]
  (let [state-atom (atom initial-state)]
    (perform-backup! state-atom, handler, file, in-wrapper)
    (let [^OutputStream file-out (FileOutputStream. file)

          ^OutputStream wrapped-out
          (if out-wrapper (wrap-out out-wrapper file-out) file-out)

          prevayler-state
          (->PrevaylerState state-atom (DataOutputStream. wrapped-out))]

      (write-event prevayler-state @state-atom)
      prevayler-state)))

(defrecord PrevaylerImpl [prevayler-state handler]
  Prevayler
  (handle! [_ event]
    (handle-event!
      handler
      (:state-atom prevayler-state)
      prevayler-state
      event))

  Closeable
  (close [_] (try-to-close prevayler-state))

  IDeref
  (deref [_] (deref (:state-atom prevayler-state))))

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
   (->PrevaylerImpl
     (new-state! initial-state handler file in-wrapper out-wrapper)
     handler)))
