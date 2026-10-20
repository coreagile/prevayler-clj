(ns prevayler.examples.crypto
  (:require [prevayler :as p])
  (:import (javax.crypto Cipher
                         CipherInputStream
                         CipherOutputStream
                         KeyGenerator)
           (java.security Key)))

(def encrypt-mode Cipher/ENCRYPT_MODE)

(def decrypt-mode Cipher/DECRYPT_MODE)

(defn aes-key []
  (.generateKey (KeyGenerator/getInstance "AES")))

(defn- get-cipher [^Integer mode, ^Key encryption-key]
  (doto (Cipher/getInstance "AES/ECB/PKCS5Padding")
    (.init mode encryption-key)))

(defn aes-cipher-in-wrapper [^Key encryption-key]
  (let [cipher (get-cipher decrypt-mode encryption-key)]
    (reify p/InWrapper
      (wrap-in [_ in]
        (CipherInputStream. in cipher)))))

(defn aes-cipher-out-wrapper [^Key encryption-key]
  (let [cipher (get-cipher encrypt-mode encryption-key)]
    (reify p/OutWrapper
      (wrap-out [_ in]
        (CipherOutputStream. in cipher)))))
