package com.vpn

import org.bouncycastle.crypto.generators.SCrypt
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM + scrypt KDF — مطابق تماماً لتطبيق Python.
 *
 * Wire format (encrypt output):
 *   [ 12-byte nonce | ciphertext | 16-byte GCM tag ]
 *
 * Nonce = 4-byte random prefix (per session) + 8-byte big-endian counter
 * Key   = scrypt(password, salt="vpn-project-v1-salt", n=2^14, r=8, p=1, len=32)
 */
object CryptoEngine {

    private val SALT = "vpn-project-v1-salt".toByteArray(Charsets.UTF_8)

    fun deriveKey(password: String): ByteArray =
        SCrypt.generate(password.toByteArray(Charsets.UTF_8), SALT, 16384, 8, 1, 32)

    class SessionCipher(key: ByteArray) {
        private val keySpec = SecretKeySpec(key, "AES")
        private val noncePrefix = ByteArray(4).also { SecureRandom().nextBytes(it) }
        private var counter = 0L

        private fun nextNonce(): ByteArray {
            counter++
            val counterBytes = ByteBuffer.allocate(8).putLong(counter).array() // big-endian
            return noncePrefix + counterBytes
        }

        fun encrypt(plaintext: ByteArray): ByteArray {
            val nonce = nextNonce()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, nonce))
            return nonce + cipher.doFinal(plaintext)
        }

        fun decrypt(data: ByteArray): ByteArray {
            require(data.size >= 28) { "Packet too short (${data.size} bytes)" }
            val nonce = data.copyOf(12)
            val ct = data.copyOfRange(12, data.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, nonce))
            return cipher.doFinal(ct)
        }
    }
}
