(ns prevayler.examples.crypto
  (:import (javax.crypto KeyGenerator Cipher CipherInputStream CipherOutputStream)
           (java.security Key)))

(def encrypt-mode Cipher/ENCRYPT_MODE)

(def decrypt-mode Cipher/DECRYPT_MODE)

(defn aes-key []
  (.generateKey (KeyGenerator/getInstance "AES")))

(defn aes-cipher-wrapper [^Integer mode, ^Key encryption-key]
  (let [cipher (doto (Cipher/getInstance "AES/ECB/PKCS5Padding")
                 (.init mode encryption-key))]
    (fn [s]
      (if (= mode Cipher/DECRYPT_MODE)
        (CipherInputStream. s cipher)
        (CipherOutputStream. s cipher)))))
