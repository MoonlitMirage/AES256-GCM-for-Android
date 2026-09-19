package com.example.myapplication

import com.example.myapplication.crypto.AesGcmCipher
import com.example.myapplication.crypto.Base64Util
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * AES-256-GCM 加解密核心逻辑的本地 JVM 单元测试。
 */
class AesGcmCipherTest {

    @Test
    fun encryptDecrypt_roundTrip_withChineseAndEmoji() {
        val key = "测试密钥-123456"
        val plain = "Hello 世界，AES-256-GCM! 🚀 中文内容 ✅"
        val cipher = AesGcmCipher.encrypt(plain, key)

        assertNotEquals("密文不应与明文相同", plain, cipher)
        assertEquals("解密结果应还原明文", plain, AesGcmCipher.decrypt(cipher, key))
    }

    @Test
    fun samePlaintext_encryptsDifferently_eachTime() {
        val a = AesGcmCipher.encrypt("同一段明文", "key-123456")
        val b = AesGcmCipher.encrypt("同一段明文", "key-123456")
        assertNotEquals("随机盐/IV 应使每次加密结果不同", a, b)
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongKey_decryptFails() {
        val cipher = AesGcmCipher.encrypt("secret", "right-key-123")
        AesGcmCipher.decrypt(cipher, "wrong-key-123")
    }

    @Test(expected = IllegalArgumentException::class)
    fun tamperedCipher_decryptFails() {
        val cipher = AesGcmCipher.encrypt("secret", "key-123456")
        val bytes = Base64Util.decode(cipher)
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 0xFF).toByte() // 篡改末尾字节
        AesGcmCipher.decrypt(Base64Util.encode(bytes), "key-123456")
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidBase64_decryptFails() {
        AesGcmCipher.decrypt("这不是合法的密文!!!", "key-123456")
    }

    @Test
    fun base64_knownVectors() {
        assertEquals("TWFu", Base64Util.encode("Man".toByteArray()))
        assertEquals("Man", String(Base64Util.decode("TWFu")))
        assertEquals("aGVsbG8=", Base64Util.encode("hello".toByteArray()))
        assertEquals("hello", String(Base64Util.decode("aGVsbG8=")))
    }

    @Test
    fun base64_roundTrip_randomBytes() {
        val random = java.util.Random(42)
        repeat(200) {
            val bytes = ByteArray(random.nextInt(1000)).also { random.nextBytes(it) }
            assertEquals(
                "随机字节 Base64 往返应一致",
                bytes.toList(),
                Base64Util.decode(Base64Util.encode(bytes)).toList()
            )
        }
    }
}
