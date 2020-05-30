(ns prevayler.s3
  (:require [prevayler :as p]
            [prevayler.locks :refer :all]
            [clojure.java.io :as io])
  (:import
   (java.net URLEncoder)
   (java.nio.charset StandardCharsets)
   (java.time.format DateTimeFormatter)
   (java.time LocalDateTime)
   (java.util.concurrent.locks ReentrantLock)
   (software.amazon.awssdk.core.sync RequestBody ResponseTransformer)
   (software.amazon.awssdk.http.apache ApacheHttpClient)
   (software.amazon.awssdk.services.s3 S3Client)
   (software.amazon.awssdk.services.s3.model
    CopyObjectRequest
    DeleteObjectRequest
    GetObjectRequest
    ListObjectsRequest
    PutObjectRequest
    S3Object)
   (java.io ByteArrayOutputStream FileOutputStream FileInputStream)
   (org.apache.commons.io.output ThresholdingOutputStream TeeOutputStream)))

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

(defn rename-key! [s3 bucket src dest]
  (let [src-url (URLEncoder/encode (format "%s/%s" bucket src)
                                   (str (StandardCharsets/UTF_8)))
        copy-r (-> (CopyObjectRequest/builder)
                   (.copySource src-url)
                   (.destinationBucket bucket)
                   (.destinationKey dest)
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

(defn download [s3 bucket key]
  (let [get-r (-> (GetObjectRequest/builder)
                  (.bucket bucket)
                  (.key key)
                  .build)]
    (.getObject s3 get-r (ResponseTransformer/toInputStream))))

(defn- realize-msg [o] (if (fn? o) (o) o))

(defn- archive! [s3 bucket key]
  (let [new-key (p/archive-name key)]
    (assert (rename-key! s3 bucket key new-key))))

(defn- keep-uploading! [sleep-time
                        active?
                        needs-upload?
                        s3
                        bucket
                        key
                        backup-contents
                        die!
                        dbg]
  (let [backup (produce-backup! dbg s3 bucket key)
        tries (atom 0)
        total-writes (atom 0)
        dbg (fn [msg] (dbg #(format "[%d] %s" @total-writes (realize-msg msg))))
        max-tries 5]
    (when backup (archive! s3 bucket backup))
    (dbg "Uploader started")
    (while @active?
      (when @needs-upload?
        (dbg "Upload needed")
        (let [upload-success? (atom false)]
          (try
            (swap! tries inc)
            (let [bytes (with-global-access-lock
                          (do
                            (reset! needs-upload? false)
                            (.toByteArray backup-contents)))
                  put-r (-> (PutObjectRequest/builder)
                            (.bucket bucket)
                            (.key key)
                            .build)]
              (dbg "Attempting upload")
              (.putObject s3 put-r (RequestBody/fromBytes bytes))
              (reset! upload-success? true)
              (dbg (fn [] (format "Uploaded bytes: %d" (count bytes))))
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

(defn backup-wrapper
  [bucket &
   {:keys [key debug? dbg-out sleep-time]
    :or {key "journal"
         sleep-time 1000

         dbg-out
         (fn [msg] (println (format "[%s] %s" (now-str) msg)))}}]
  (fn [out-stream]
    (let [s3 (s3-client)
          dbg-out (or dbg-out
                      (fn [msg] (println (format "[%s] %s" (now-str) msg))))
          dbg (if debug? (fn [o] (dbg-out (realize-msg o)))
                         identity)
          s3-flush-active? (atom true)
          needs-upload? (atom false)
          backup-contents (ByteArrayOutputStream.)

          die! (fn []
                 (if @s3-flush-active?
                   (let [tries (atom 0)]
                     (while (and @needs-upload? (< @tries 25))
                       (swap! tries inc)
                       (Thread/sleep 100))

                     (if @needs-upload?
                       (binding [*out* *err*]
                         (println "WARNING: S3 sync never finished")))

                     (with-global-access-lock
                       (dbg "Termination requested")
                       (reset! s3-flush-active? false)))
                   (dbg "Already terminated")))

          cause-upload! (partial reset! needs-upload? true)

          threshholding-stream
          (proxy [ThresholdingOutputStream] [1]
            (getStream [] backup-contents)
            (thresholdReached []
              (cause-upload!)
              (proxy-super resetByteCount))
            (close []
              (proxy-super close)

             ;; In case we are wrapping a stream that writes extra bytes during
             ;; close -- thresholdReached isn't called during stream closing
              (cause-upload!)
              (die!)))]
      (future (keep-uploading! sleep-time
                               s3-flush-active?
                               needs-upload?
                               s3
                               bucket
                               key
                               backup-contents
                               die!
                               dbg))
      (TeeOutputStream. out-stream threshholding-stream))))

(comment
 (with-open [fout (java.io.FileOutputStream. "test.txt")
             wrapped-out ((backup-wrapper (System/getenv "PREVAYLER_BUCKET")
                                          :debug? true
                                          :key "test") fout)
             writer (java.io.OutputStreamWriter. wrapped-out)
             print-writer (java.io.PrintWriter. writer)]
   (dorun (map (fn [n] (.println print-writer (format "hello %d" n)))
               (range 100))))

 *e
 )