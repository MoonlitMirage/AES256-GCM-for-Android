package com.example.myapplication.crypto

/**
 * 标准 RFC 4648 Base64 编解码（无换行，支持可选填充 `=`）。
 *
 * 不依赖 android.util.Base64，方便在本地 JVM 单元测试中直接验证加解密逻辑。
 */
object Base64Util {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val DECODE = IntArray(256) { -1 }.also { table ->
        ALPHABET.forEachIndexed { i, c -> table[c.code] = i }
    }

    fun encode(data: ByteArray): String {
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        val n = data.size
        while (i < n) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < n) data[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < n) data[i + 2].toInt() and 0xFF else -1

            sb.append(ALPHABET[b0 shr 2])
            sb.append(ALPHABET[((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            sb.append(if (b1 >= 0) ALPHABET[((b1 and 0x0F) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)] else '=')
            sb.append(if (b2 >= 0) ALPHABET[b2 and 0x3F] else '=')
            i += 3
        }
        return sb.toString()
    }

    fun decode(input: String): ByteArray {
        val s = input.filter { it != '=' && it != '\r' && it != '\n' && it != ' ' && it != '\t' }
        require(s.isNotEmpty()) { "invalid base64" }
        val out = ByteArray(s.length / 4 * 3 + 3)
        var olen = 0
        var i = 0
        val n = s.length
        while (i < n) {
            val a = DECODE[s[i].code]
            val b = if (i + 1 < n) DECODE[s[i + 1].code] else 0
            val c = if (i + 2 < n) DECODE[s[i + 2].code] else 0
            val d = if (i + 3 < n) DECODE[s[i + 3].code] else 0
            require(a >= 0 && b >= 0 && c >= 0 && d >= 0) { "invalid base64" }

            out[olen++] = ((a shl 2) or (b shr 4)).toByte()
            if (i + 2 < n) out[olen++] = ((b shl 4) or (c shr 2)).toByte()
            if (i + 3 < n) out[olen++] = ((c shl 6) or d).toByte()
            i += 4
        }
        return out.copyOf(olen)
    }
}
