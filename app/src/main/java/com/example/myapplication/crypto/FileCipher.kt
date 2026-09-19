package com.example.myapplication.crypto

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 文件流式加解密（AES-256-GCM，不把整个文件读进内存，任意大小文件不卡 UI）。
 *
 * 文件格式：
 *   [0:4]   magic "CYPT"（识别是否为本工具加密的文件）
 *   [4:20]  随机盐 16B
 *   [20:32] 随机 IV 12B
 *   [32:..] AES-GCM 密文流 + 128 位认证标签（末尾）
 *
 * - 密钥：用户密码经 PBKDF2-HMAC-SHA256（60 万次迭代）派生 256 位密钥
 * - 每次加密随机盐 + 随机 IV，即使 nonce 碰撞也因密钥不同而安全
 * - 解密在流末尾校验认证标签：密钥错误或文件被篡改时明确失败
 */
object FileCipher {

    private const val MAGIC = "CYPT"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 600_000
    private const val BUFFER_SIZE = 64 * 1024

    private val secureRandom = SecureRandom()

    /** 加密：input 流 -> output 流（写入 CYPT 头部 + GCM 密文流）。失败抛 IOException / IllegalArgumentException */
    fun encryptFile(input: InputStream, output: OutputStream, password: String) {
        val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { secureRandom.nextBytes(it) }
        val key = deriveKey(password, salt)

        output.write(MAGIC.toByteArray(Charsets.US_ASCII))
        output.write(salt)
        output.write(iv)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        // close 时写入 GCM 认证标签
        CipherOutputStream(output, cipher).use { cos ->
            input.copyTo(cos, BUFFER_SIZE)
        }
    }

    /** 解密：input 流（CYPT 格式）-> output 流。密钥错误/文件损坏抛 IllegalArgumentException（带用户可读信息） */
    fun decryptFile(input: InputStream, output: OutputStream, password: String) {
        val magic = ByteArray(4)
        readFully(input, magic)
        if (String(magic, Charsets.US_ASCII) != MAGIC) {
            throw IllegalArgumentException("不是本工具加密的文件")
        }
        val salt = ByteArray(SALT_BYTES).also { readFully(input, it) }
        val iv = ByteArray(IV_BYTES).also { readFully(input, it) }

        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))

        try {
            CipherInputStream(input, cipher).use { cis ->
                cis.copyTo(output, BUFFER_SIZE)
            }
        } catch (e: GeneralSecurityException) {
            throw IllegalArgumentException("解密失败：密钥错误或文件已损坏")
        } catch (e: IOException) {
            if (e.cause is GeneralSecurityException) {
                throw IllegalArgumentException("解密失败：密钥错误或文件已损坏")
            }
            throw e
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IllegalArgumentException("文件不完整或不是本工具加密的文件")
            off += n
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }
}
