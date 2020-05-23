(ns prevayler-s3
  (:require [prevayler :as p])
  (:import
   (clojure.lang IDeref)
   (java.io ByteArrayOutputStream Closeable DataInputStream DataOutputStream)
   (java.net URLEncoder)
   (java.nio.charset StandardCharsets)
   (java.time.format DateTimeFormatter)
   (java.time LocalDateTime)
   (java.util.concurrent.locks ReentrantLock)
   (java.util.concurrent TimeUnit)
   (software.amazon.awssdk.core.sync RequestBody ResponseTransformer)
   (software.amazon.awssdk.http.apache ApacheHttpClient)
   (software.amazon.awssdk.services.s3 S3Client)
   (software.amazon.awssdk.services.s3.model
    CopyObjectRequest
    DeleteObjectRequest
    GetObjectRequest
    ListObjectsRequest
    PutObjectRequest
    S3Object)))

(def ^:private date-formatter
  (DateTimeFormatter/ofPattern "uuuu-MM-dd'T'HH:mm:ss.SSS"))

(defn now-str []
  (.format date-formatter (LocalDateTime/now)))

(defn- key-exists? [^S3Client s3 bucket key]
  (let [r (-> (ListObjectsRequest/builder)
              (.bucket bucket)
              .build)
        bucket-keys (->> r
                         (.listObjects s3)
                         .contents
                         (map (fn [^S3Object o] (.key o)))
                         set)]
    (-> key bucket-keys boolean)))

(defn- rename-key! [s3 bucket src dest]
  (let [src-url (URLEncoder/encode (format "%s/%s" bucket src)
                                   (str (StandardCharsets/UTF_8)))
        copy-r (-> (CopyObjectRequest/builder)
                   (.copySource src-url)
                   (.bucket bucket)
                   (.key dest)
                   .build)
        delete-r (-> (DeleteObjectRequest/builder)
                     (.bucket bucket)
                     (.key src)
                     .build)]
    (.copyObject s3 copy-r)
    (.deleteObject s3 delete-r)
    true))

(defn- produce-backup! [dbg s3 bucket key]
  (let [backup (p/backup-name key)]
    (if (key-exists? s3 bucket backup)
      (do
        (dbg (format "Using backup found at '%s'" backup))
        backup)
      (if (key-exists? s3 bucket key)
        (do
          (dbg (format "Renaming '%s' to '%s'" key backup))
          (assert (rename-key! s3 bucket key backup))
          backup)
        (dbg (format "'%s' not found" key))))))

(defn- s3-client []
  (-> (S3Client/builder)
      (.httpClientBuilder (ApacheHttpClient/builder))
      .build))

(defn- download [s3 bucket key]
  (let [get-r (-> (GetObjectRequest/builder)
                  (.bucket bucket)
                  (.key key)
                  .build)]
    (.getObject s3 get-r (ResponseTransformer/toInputStream))))

(defmacro with-access-lock [^ReentrantLock access-lock & body]
  `(try
     (if (or (.tryLock ~access-lock)
             (.tryLock ~access-lock 5 TimeUnit/SECONDS))
       (do ~@body)
       (throw (ex-info "Failed to acquire access lock" {})))
     (finally
       (.unlock ~access-lock))))

(defn- keep-uploading! [sleep-time
                        ^ReentrantLock byte-access-lock
                        active?
                        needs-upload?
                        s3
                        bucket
                        key
                        session-bytes
                        die!
                        dbg]
  (let [tries (atom 0)
        total-writes (atom 0)
        dbg (fn [msg] (dbg #(format "[%d] %s" @total-writes msg)))
        max-tries 5]
    (dbg "Uploader started")
    (while @active?
      (when @needs-upload?
        (dbg "Upload needed")
        (let [upload-success? (atom false)]
          (try
            (swap! tries inc)
            (let [bytes (with-access-lock byte-access-lock
                          (if @session-bytes
                            (do
                              (reset! needs-upload? false)
                              (.toByteArray @session-bytes))
                            (throw
                             (ex-info "session-bytes gone -- closed?"
                                      {:die-immediately? true}))))
                  put-r (-> (PutObjectRequest/builder)
                            (.bucket bucket)
                            (.key key)
                            .build)]
              (dbg "Attempting upload")
              (.putObject s3 put-r (RequestBody/fromBytes bytes))
              (reset! upload-success? true)
              (dbg "Upload success")
              (reset! tries 0))
            (catch Throwable t
              (println "FAILED to complete S3 upload")
              (.printStackTrace t)
              (if (not @upload-success?) (reset! needs-upload? true))
              (when (or (>= @tries max-tries)
                        (-> t ex-data :die-immediately?))
                (println "Giving up. Tries:" @tries)
                (die!)))
            (finally (swap! total-writes inc)))))
      (Thread/sleep sleep-time))
    (dbg "Uploader exiting")))

(defn- trigger-upload! [^ReentrantLock byte-access-lock
                        s3-flush-active?
                        needs-upload?
                        session-bytes
                        data-out
                        value]
  (if (and @data-out @session-bytes @s3-flush-active?)
    (with-access-lock byte-access-lock
      (p/write-with-flush! @data-out value)
      (reset! needs-upload? true))
    (throw (ex-info "Prevayler closed. Unable to persist"
                    {:session-bytes @session-bytes, :data-out @data-out}))))

(defn- archive! [s3 bucket key]
  (let [new-key (p/archive-name key)]
    (assert (rename-key! s3 bucket key new-key))))

(defn prevayler! [handler &
                  {:keys [bucket key initial-state debug? dbg-out sleep-time]
                   :or {key "journal"
                        initial-state {}
                        sleep-time 1000

                        dbg-out
                        (fn [msg] (println (format "[%s] %s" (now-str) msg)))}}]
  (when-not bucket
    (throw (ex-info ":bucket not specified" {})))

  (let [s3 (s3-client)
        state-atom (atom initial-state)
        byte-access-lock (ReentrantLock.)
        needs-upload? (atom false)
        session-bytes (atom (ByteArrayOutputStream.))
        data-out (atom (DataOutputStream. @session-bytes))
        dbg (if debug? (fn [o] (dbg-out (if (fn? o) (o) o)))
                       identity)
        backup (produce-backup! dbg s3 bucket key)
        s3-flush-active? (atom true)
        die! (fn []
               (with-access-lock byte-access-lock
                 (dbg "Termination requested")
                 (reset! s3-flush-active? false)
                 (reset! data-out nil)
                 (reset! session-bytes nil)
                 (reset! state-atom ::closed)))
        s3-upload-future (future (keep-uploading! sleep-time
                                                  byte-access-lock
                                                  s3-flush-active?
                                                  needs-upload?
                                                  s3
                                                  bucket
                                                  key
                                                  session-bytes
                                                  die!
                                                  dbg))]
    (when backup
      (dbg (format "Restoring from '%s'" backup))
      (with-open [content-stream (download s3 bucket backup)
                  data-in (DataInputStream. content-stream)]
        (p/restore! handler state-atom data-in)))

    (let [write!
          (partial trigger-upload!
                   byte-access-lock
                   s3-flush-active?
                   needs-upload?
                   session-bytes
                   data-out)]
      (write! @state-atom)
      (when backup
        (dbg (format "Archiving '%s'" backup))
        (archive! s3 bucket backup))

      (reify
        p/Prevayler
        (handle! [this event]
          (p/handle-event! this handler state-atom write! event))

        Closeable
        (close [_]
          (die!)
          (dbg "Waiting for S3 upload thread to complete")
          @s3-upload-future)

        IDeref
        (deref [_] @state-atom)))))

(comment
 (defn- handler [state event]
   (when (= "boom" event) (throw (RuntimeException.)))
   (if (= "reset" event)
     ["" ""]
     [(str state event)
      (str "+" event)]))

 (def bucket (System/getenv "PREVAYLER_BUCKET"))

 (def p (prevayler! handler
                    :bucket bucket
                    :initial-state ""
                    :debug? true
                    :sleep-time 0))

 (def futures (->> (range 100)
                   (map
                    (fn [n]
                      (future
                       (Thread/sleep (+ 1000 (* 10 n)))
                       (p/handle! p (str "\n" (java.util.Date.) " -- " n))
                       "success")))
                   doall))

 (time (p/handle! p "\n---"))

 (p/handle! p "reset")

 (p/handle! p "boom")

 (println @p)

 (.close p)

 *e)

