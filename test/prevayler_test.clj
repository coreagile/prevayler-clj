(ns prevayler-test
  (:require [midje.sweet :refer :all]
            [prevayler :refer :all])
  (:import [java.io File]
           (clojure.lang ExceptionInfo)
           (javax.crypto Cipher SecretKeyFactory)
           (javax.crypto.spec IvParameterSpec PBEKeySpec SecretKeySpec)
           (java.security SecureRandom)))

; (do (require 'midje.repl) (midje.repl/autotest))

(defn- handler [state event]
  (when (= event "boom") (throw (RuntimeException.)))
  [(str state event)
   (str "+" event)])

(def initial-state "A")

(defn- tmp-file []
  (doto
    (File/createTempFile "test-" ".tmp")
    (.delete)))

(facts "About transient prevayler"
  (with-open [p (transient-prevayler! handler initial-state)]
    @p => "A"
    (handle! p "B") => ["AB" "+B"]
    @p => "AB"))

(defmacro test-prevayler [file prev!]
  `(do
     (fact "First run uses initial state"
       (with-open [p# (~prev!)]
         @p# => "A"))

     (fact "Restart after no events recovers initial state"
       (with-open [p# (~prev!)]
         @p# => "A"
         (handle! p# "B") => ["AB" "+B"]
         @p# => "AB"
         (handle! p# "C") => ["ABC" "+C"]
         @p# => "ABC"
         (eval! p# "D") => "+D"
         @p# => "ABCD"
         (step! p# "E") => "ABCDE"
         @p# => "ABCDE"))

     (fact "Restart after some events recovers last state"
       (with-open [p# (~prev!)]
         @p# => "ABCDE"))

     (fact "Simulated crash during restart is survived"
       (assert (.renameTo ~file (backup-file ~file)))
       (spit ~file "#$@%@corruption&@#$@")
       (with-open [p# (~prev!)]
         @p# => "ABCDE"))

     (fact "Simulated crash during event handle will fall through"
       (with-open [p# (~prev!)]
         (handle! p# "boom") => (throws RuntimeException)
         @p# => "ABCDE"
         (step! p# "F") => "ABCDEF"))

     (fact "Restart after some crash during event handle recovers last state"
       (with-open [p# (~prev!)]
         @p# => "ABCDEF"))

     (fact "File is released after Prevayler is closed"
       (assert (.delete ~file)))))

(facts "About prevalence"
  (let [file (tmp-file)]
    (test-prevayler file #(prevayler! handler initial-state file))))

(defn- make-ciphers [salt password]
  (let [key-type "PBKDF2WithHmacSHA256"
        cipher-type "AES/CBC/PKCS5Padding"
        key (-> (SecretKeyFactory/getInstance key-type)
                (.generateSecret
                  (PBEKeySpec. (.toCharArray password) salt 40000 128))
                (.getEncoded)
                (SecretKeySpec. "AES"))
        enc-cipher (doto
                     (Cipher/getInstance cipher-type)
                     (.init Cipher/ENCRYPT_MODE key))
        params (-> enc-cipher
                   (.getParameters)
                   (.getParameterSpec IvParameterSpec))
        dec-cipher (doto
                     (Cipher/getInstance cipher-type)
                     (.init Cipher/DECRYPT_MODE key params))]
    [enc-cipher dec-cipher]))

(facts "About prevalence using encryption"
  (let [file (tmp-file)
        random (SecureRandom.)
        salt (byte-array 20)
        _ (.nextBytes random salt)

        [enc-cipher dec-cipher] (make-ciphers salt "good!")
        [bad-enc bad-dec] (make-ciphers salt "wrong!")
        encprev! #(prevayler! handler initial-state file enc-cipher dec-cipher)
        badprev! #(prevayler! handler initial-state file bad-enc bad-dec)]
    (test-prevayler file encprev!)

    (fact "Can't read encrypted state"
      (with-open [p (encprev!)]
        (handle! p "BCDE")
        @p) => "ABCDE"
      (with-open [p (encprev!)]
        @p) => "ABCDE")
    (fact "Won't destroy the journal if it fails to read"
      (with-open [p (badprev!)]
        @p) => (throws ExceptionInfo bad-cipher)
      (with-open [p (prevayler! handler initial-state file)]
        @p) => (throws ExceptionInfo bad-journal)
      (with-open [p (encprev!)]
        @p) => "ABCDE")
    (fact "File is released after Prevayler is closed"
      (assert (.delete file)))))