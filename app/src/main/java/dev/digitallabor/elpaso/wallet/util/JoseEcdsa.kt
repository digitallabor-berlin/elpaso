package dev.digitallabor.elpaso.wallet.util

import android.util.Base64

/** base64url no-padding helpers. */
object B64u {
    private val FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
    fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, FLAGS)
    fun encode(s: String): String = encode(s.toByteArray(Charsets.UTF_8))
    fun decode(s: String): ByteArray = Base64.decode(s, FLAGS)
}

/**
 * Convert ASN.1 DER-encoded ECDSA signature (produced by `SHA256withECDSA`) to the
 * fixed-length R||S concatenation that JOSE expects. For P-256, [partLen] is 32.
 */
object JoseEcdsa {
    fun derToJose(der: ByteArray, partLen: Int = 32): ByteArray {
        var i = 2
        if (der[1].toInt() and 0x80 != 0) i += (der[1].toInt() and 0x7F)
        i++ // skip 0x02 tag for r
        val rLen = der[i].toInt() and 0xFF; i++
        val r = der.copyOfRange(i, i + rLen); i += rLen
        i++ // skip 0x02 tag for s
        val sLen = der[i].toInt() and 0xFF; i++
        val s = der.copyOfRange(i, i + sLen)
        return leftPad(stripLeadingZeros(r), partLen) + leftPad(stripLeadingZeros(s), partLen)
    }

    private fun stripLeadingZeros(bs: ByteArray): ByteArray {
        var idx = 0
        while (idx < bs.size - 1 && bs[idx] == 0.toByte()) idx++
        return bs.copyOfRange(idx, bs.size)
    }

    private fun leftPad(bs: ByteArray, len: Int): ByteArray {
        if (bs.size == len) return bs
        if (bs.size > len) return bs.copyOfRange(bs.size - len, bs.size)
        val out = ByteArray(len)
        System.arraycopy(bs, 0, out, len - bs.size, bs.size)
        return out
    }
}
