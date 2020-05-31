(ns prevayler-test
  (:require [midje.sweet :refer :all]
            [prevayler :refer :all :as p]
            [prevayler.examples.crypto :as crypto])
  (:import [java.io File IOException]
           (clojure.lang ExceptionInfo)
           (javax.crypto Cipher)))

; (do (require 'midje.repl) (midje.repl/autotest))

(defn- handler [state event]
  (when (= event "boom") (throw (RuntimeException.)))
  [(str state event)
   (str "+" event)])

(def initial-state "A")

(defn- tmp-file
  ([] (tmp-file "test"))
  ([prefix]
   (doto
     (File/createTempFile (str prefix "-") ".tmp"
                          (doto (File. "./target/.test-files") (.mkdirs)))
     (.delete))))

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

(defn- crypto-wrappers []
  (let [key (crypto/aes-key)]
    [(crypto/aes-cipher-wrapper crypto/encrypt-mode key)
     (crypto/aes-cipher-wrapper crypto/decrypt-mode key)]))

(facts "About prevalence using encryption"
  (let [file (tmp-file "enc-test")
        [enc dec] (crypto-wrappers)
        [bad-enc bad-dec] (crypto-wrappers)
        encprev! #(prevayler! handler initial-state file enc dec)
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
        @p) => (throws IOException)
      (with-open [p (prevayler! handler initial-state file)]
        @p) => (throws ExceptionInfo bad-journal)
      (with-open [p (encprev!)]
        @p) => "ABCDE")
    (fact "File is released after Prevayler is closed"
      (assert (.delete file)))))