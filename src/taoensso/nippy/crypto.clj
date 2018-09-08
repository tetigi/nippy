(ns taoensso.nippy.crypto
  "Low-level crypto utils.
  Private & alpha, very likely to change!"
  (:require [taoensso.encore :as enc]))

;; Note that AES128 may be preferable to AES256 due to known attack
;; vectors specific to AES256, Ref. https://goo.gl/qU4CCV
;;  or for a counter argument, Ref. https://goo.gl/9LA9Yb

;;;; Randomness

(do
  (def ^:private prng* (enc/thread-local-proxy (java.security.SecureRandom/getInstance "SHA1PRNG")))
  (defn prng ^java.security.SecureRandom  [] (.get ^ThreadLocal prng*))
  (defn rand-bytes [size] (let [ba (byte-array size)] (.nextBytes (prng) ba) ba)))

(comment (seq (rand-bytes 16)))

;;;; Key derivation (salt+password -> key / hash)
;; (fn [salt-ba utf8]) -> bytes

;; (defn  ba->hex [^bytes ba] (org.apache.commons.codec.binary.Hex/encodeHexString ba))
(defn  take-ba [n ^bytes ba] (java.util.Arrays/copyOf ba ^int n)) ; Pads if ba too small
(defn utf8->ba [^String s] (.getBytes s "UTF-8"))

(def ^:private sha512-md* (enc/thread-local-proxy (java.security.MessageDigest/getInstance "SHA-512")))
(defn  sha512-md ^java.security.MessageDigest [] (.get ^ThreadLocal sha512-md*))
(defn  sha512-ba
  "SHA512-based key generator. Good JVM availability without extra dependencies
  (PBKDF2, bcrypt, scrypt, etc.). Decent security when using many rounds."
  ([salt-ba         utf8               ] (sha512-ba salt-ba utf8 (* Short/MAX_VALUE 5)))
  ([salt-ba ^String utf8 ^long n-rounds]
   (let [md (sha512-md)
         ba (let [pwd-ba (.getBytes utf8 "UTF-8")]
              (if salt-ba
                (enc/ba-concat salt-ba pwd-ba)
                pwd-ba))]

     (enc/reduce-n (fn [acc in] (.digest md acc)) ba n-rounds))))

(comment
  (count (seq (sha512-ba (utf8->ba "salt") "password" 1)))
  (count (seq (sha512-ba nil               "password" 1))))

;;;; Crypto

(defprotocol ICipherKit
  (get-cipher     ^javax.crypto.Cipher [_] "Returns a thread-safe `javax.crypto.Cipher` instance.")
  (get-iv-size                         [_] "Returns necessary iv-ba length.")
  (get-key-spec   ^javax.crypto.spec.SecretKeySpec           [_    ba] "Returns a `javax.crypto.spec.SecretKeySpec`.")
  (get-param-spec ^java.security.spec.AlgorithmParameterSpec [_ iv-ba] "Returns a `java.security.spec.AlgorithmParameters`."))

;; Prefer GCM > CBC, Ref. https://goo.gl/jpZoj8
(def ^:private gcm-cipher* (enc/thread-local-proxy (javax.crypto.Cipher/getInstance "AES/GCM/NoPadding")))
(def ^:private cbc-cipher* (enc/thread-local-proxy (javax.crypto.Cipher/getInstance "AES/CBC/PKCS5Padding")))

(defn gcm-cipher ^javax.crypto.Cipher [] (.get ^ThreadLocal gcm-cipher*))
(defn cbc-cipher ^javax.crypto.Cipher [] (.get ^ThreadLocal cbc-cipher*))
;
(deftype CipherKit-AES-GCM []
  ICipherKit
  (get-cipher     [_] (gcm-cipher))
  (get-iv-size    [_] 12)
  (get-key-spec   [_    ba] (javax.crypto.spec.SecretKeySpec. ba "AES"))
  (get-param-spec [_ iv-ba] (javax.crypto.spec.GCMParameterSpec. 128 iv-ba)))

(deftype CipherKit-AES-CBC []
  ICipherKit
  (get-cipher     [_] (cbc-cipher))
  (get-iv-size    [_] 16)
  (get-key-spec   [_    ba] (javax.crypto.spec.SecretKeySpec. ba "AES"))
  (get-param-spec [_ iv-ba] (javax.crypto.spec.IvParameterSpec. iv-ba)))

(def cipher-kit-aes-gcm "Default CipherKit for AES GCM" (CipherKit-AES-GCM.))
(def cipher-kit-aes-cbc "Default CipherKit for AES CBC" (CipherKit-AES-CBC.))

;;  Output bytes: [         <iv>            <?salt> <encrypted>]
;; Could also do: [<iv-len> <iv> <salt-len> <?salt> <encrypted>]
(defn encrypt [cipher-kit ?salt-ba key-ba ba]
  (let [iv-size     (long (get-iv-size cipher-kit))
        iv-ba       (rand-bytes iv-size)
        prefix-ba   (if ?salt-ba (enc/ba-concat iv-ba ?salt-ba) iv-ba)
        key-spec    (get-key-spec   cipher-kit key-ba)
        param-spec  (get-param-spec cipher-kit iv-ba)
        cipher      (get-cipher     cipher-kit)]

    (.init cipher javax.crypto.Cipher/ENCRYPT_MODE key-spec param-spec)
    (enc/ba-concat prefix-ba (.doFinal cipher ba))))

(comment (encrypt cipher-kit-aes-gcm nil (take-ba 16 (sha512-ba nil "pwd")) (utf8->ba "data")))

(defn decrypt [cipher-kit salt-size salt->key-fn ba]
  (let [salt-size           (long salt-size)
        iv-size             (long (get-iv-size cipher-kit))
        prefix-size         (+ iv-size salt-size)
        [prefix-ba data-ba] (enc/ba-split ba prefix-size)
        [iv-ba salt-ba]     (if (pos? salt-size)
                              (enc/ba-split prefix-ba iv-size)
                              [prefix-ba nil])

        key-ba     (salt->key-fn salt-ba)
        key-spec   (get-key-spec   cipher-kit key-ba)
        param-spec (get-param-spec cipher-kit iv-ba)
        cipher     (get-cipher     cipher-kit)]

    (.init cipher javax.crypto.Cipher/DECRYPT_MODE key-spec param-spec)
    (.doFinal cipher data-ba)))

(comment
  (do
    (defn sha512-16 [?salt-ba pwd] (take-ba 16 (sha512-ba ?salt-ba pwd)))
    (defn roundtrip [kit ?salt-ba key-ba key-fn]
      (let [salt-size (count ?salt-ba)
            encr (encrypt kit ?salt-ba  key-ba (utf8->ba "data"))
            decr (decrypt kit salt-size key-fn encr)]
        (String. ^bytes decr "UTF-8")))

    [(let [s (rand-bytes 16)] (roundtrip cipher-kit-aes-gcm s (sha512-16 s "pwd") #(sha512-16 % "pwd")))
     (let [s             nil] (roundtrip cipher-kit-aes-gcm s (sha512-16 s "pwd") #(sha512-16 % "pwd")))
     (let [s (rand-bytes 16)] (roundtrip cipher-kit-aes-cbc s (sha512-16 s "pwd") #(sha512-16 % "pwd")))
     (let [s             nil] (roundtrip cipher-kit-aes-cbc s (sha512-16 s "pwd") #(sha512-16 % "pwd")))])

  (enc/qb 10
    (roundtrip "foo" {})
    (roundtrip "foo" {:cipher-kit cipher-kit-aes-cbc}))
  ;; [2348.05 2332.25]
  )