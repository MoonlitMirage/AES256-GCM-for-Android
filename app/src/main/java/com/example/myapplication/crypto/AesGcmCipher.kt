package com.example.myapplication.crypto

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM 字符串加解密。
 *
 * 密文为自包含的 Base64 字符串，可直接复制粘贴到 QQ / 微信：
 *   Base64( 随机盐 16B + 随机 IV 12B + AES-GCM 密文及 16B 认证标签 )
 *
 * - 密钥：用户输入的任意字符串，经 PBKDF2-HMAC-SHA256（60 万次迭代）派生为 256 位
 * - 每次加密使用随机盐和随机 IV，同一明文多次加密结果不同
 * - GCM 自带完整性校验：密钥错误或密文被篡改时解密会明确失败
 */
object AesGcmCipher {

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 600_000

    private val secureRandom = SecureRandom()

    /** 加密：明文 + 密钥 -> Base64 密文 */
    fun encrypt(plaintext: String, password: String): String {
        val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
        val key = deriveKey(password, salt)
        val iv = ByteArray(IV_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64Util.encode(salt + iv + encrypted)
    }

    /** 解密：Base64 密文 + 密钥 -> 明文；失败时抛出带用户可读信息的 [IllegalArgumentException] */
    fun decrypt(cipherText: String, password: String): String {
        val data = try {
            Base64Util.decode(cipherText)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("无效的密文格式")
        }
        if (data.size < SALT_BYTES + IV_BYTES + 1) {
            throw IllegalArgumentException("无效的密文格式")
        }
        val salt = data.copyOfRange(0, SALT_BYTES)
        val iv = data.copyOfRange(SALT_BYTES, SALT_BYTES + IV_BYTES)
        val encrypted = data.copyOfRange(SALT_BYTES + IV_BYTES, data.size)

        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val plain = try {
            cipher.doFinal(encrypted)
        } catch (e: GeneralSecurityException) {
            throw IllegalArgumentException("解密失败：密钥错误或密文已被篡改")
        }
        return String(plain, Charsets.UTF_8)
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }
}
