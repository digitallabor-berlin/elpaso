package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64

/**
 * Image bytes together with the media type they were served or declared under.
 *
 * Carried separately from [ImageSource] because it is also what the remote resolver
 * produces after a fetch: both channels converge on bytes-plus-type before anything is
 * drawn, so the composable never learns whether an image came from the wire.
 */
class ResolvedImage(
    val bytes: ByteArray,
    val mediaType: String,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is ResolvedImage && mediaType == other.mediaType && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + mediaType.hashCode()

    override fun toString(): String = "ResolvedImage(mediaType=$mediaType, bytes=${bytes.size})"
}

/**
 * RFC2397 `data:` URL parsing.
 *
 * A data URL is the form PaSO View §5.3 recommends, because it needs no network: the
 * bytes arrive inside the request, so resolving the image cannot reveal the transaction's
 * timing or the wallet's address to a third-party host.
 *
 * Every malformed input returns null rather than throwing. A verifier controls this
 * string, so "unparseable" is an ordinary outcome to be turned into an incompatible
 * entry — not an exception to be caught somewhere up the stack.
 */
object DataUrl {
    private const val SCHEME = "data:"
    private const val BASE64_MARKER = ";base64"

    /** RFC2397 §2: "If <mediatype> is omitted, it defaults to text/plain". */
    private const val DEFAULT_MEDIA_TYPE = "text/plain"

    fun parse(value: String): ResolvedImage? {
        if (!value.startsWith(SCHEME, ignoreCase = true)) return null
        val body = value.substring(SCHEME.length)
        val comma = body.indexOf(',')
        if (comma < 0) return null

        val header = body.substring(0, comma)
        val data = body.substring(comma + 1)
        val isBase64 = header.endsWith(BASE64_MARKER, ignoreCase = true)

        // `image/png;base64` → `image/png`; `;base64` → default; `text/html;charset=utf-8`
        // → `text/html`. Parameters other than the base64 marker are not carried: the
        // media type is all the decoder downstream needs.
        val mediaType =
            header
                .let { if (isBase64) it.dropLast(BASE64_MARKER.length) else it }
                .substringBefore(';')
                .trim()
                .ifBlank { DEFAULT_MEDIA_TYPE }

        val bytes =
            if (isBase64) {
                // The strict decoder, deliberately: the MIME decoder silently skips
                // characters it does not recognise, so garbage would decode to *something*
                // and a malformed data URL would render as an image rather than being
                // refused.
                runCatching { Base64.getDecoder().decode(data) }.getOrNull() ?: return null
            } else {
                runCatching { URLDecoder.decode(data, Charsets.UTF_8).toByteArray() }.getOrNull() ?: return null
            }

        return ResolvedImage(bytes, mediaType)
    }
}

/**
 * [W3C.SRI] integrity metadata — the hash PaSO View §3 requires alongside every non-data
 * image URL.
 *
 * The hash is what makes a remote image safe to show at all: the wallet fetches from a
 * host the verifier named, and without a hash pinned by the *issuer's* signed metadata,
 * that host could serve anything at consent time.
 */
object Sri {
    enum class Alg(
        val jca: String,
        val prefix: String,
    ) {
        SHA256("SHA-256", "sha256"),
        SHA384("SHA-384", "sha384"),
        SHA512("SHA-512", "sha512"),
    }

    data class Hash(
        val alg: Alg,
        val digestBase64: String,
    )

    /**
     * Parses `<alg>-<base64digest>`.
     *
     * Only SHA-256/384/512 are accepted, matching the SRI specification's own list —
     * MD5 and SHA-1 are absent deliberately, since a collision-prone hash would let a
     * host serve different bytes than the issuer signed for.
     */
    fun parse(integrity: String): Hash? {
        val dash = integrity.indexOf('-')
        if (dash <= 0) return null
        val prefix = integrity.substring(0, dash).lowercase()
        val digest = integrity.substring(dash + 1)
        if (digest.isEmpty()) return null
        val alg = Alg.entries.firstOrNull { it.prefix == prefix } ?: return null
        // Reject now rather than at verification time: an unparseable digest means the
        // entry can never be verified, which is a metadata problem, not a fetch problem.
        val decoded = runCatching { Base64.getDecoder().decode(digest) }.getOrNull() ?: return null
        if (decoded.isEmpty()) return null
        return Hash(alg, digest)
    }

    /**
     * Whether [content] hashes to [hash].
     *
     * Uses [MessageDigest.isEqual], which is the constant-time comparison — not because
     * an image digest is secret, but because a timing-variable compare in a verification
     * path is the kind of thing that gets copied into one where it matters.
     */
    fun verify(
        content: ByteArray,
        hash: Hash,
    ): Boolean {
        val expected = runCatching { Base64.getDecoder().decode(hash.digestBase64) }.getOrNull() ?: return false
        val actual = MessageDigest.getInstance(hash.alg.jca).digest(content)
        return MessageDigest.isEqual(expected, actual)
    }
}
