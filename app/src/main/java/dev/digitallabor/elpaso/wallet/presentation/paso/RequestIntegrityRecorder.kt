package dev.digitallabor.elpaso.wallet.presentation.paso

import android.util.Base64
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Captures the verbatim bytes of fetched JAR Authorization Request JWTs, keyed by their
 * `request_uri` URL. Required so the SCA Response Claims can include `request_integrity`
 * (a W3C SRI value computed over the compact-serialised JWT as received by the wallet —
 * PaSO Core §6.1).
 *
 * The OkHttp interceptor wired into [dev.digitallabor.elpaso.wallet.data.network.HttpClientFactory]
 * records the body of any response whose content type is `application/oauth-authz-req+jwt`
 * or whose body is a recognisable compact-JWS. `PresentationClient.resolveDeepLink` then
 * looks the URL up post-resolution. Inline `request=<jwt>` values (no fetch) are handled
 * by hashing the JWT directly without going through this cache — see [hashJwt].
 *
 * Entries are evicted on read so the cache stays bounded across the app's lifetime.
 */
object RequestIntegrityRecorder {

    private val captured = ConcurrentHashMap<String, ByteArray>()

    /** Called from the OkHttp interceptor when a JAR JWT response body is observed. */
    fun record(url: String, bytes: ByteArray) {
        captured[url] = bytes
    }

    /** Pop the captured bytes for [url], compute the SRI value, or null if not recorded. */
    fun consume(url: String): String? {
        val bytes = captured.remove(url) ?: return null
        return sriFromBytes(bytes)
    }

    /** Hash an inline `request=<jwt>` value directly (no HTTP fetch involved). */
    fun hashJwt(compactJws: String): String =
        sriFromBytes(compactJws.toByteArray(Charsets.US_ASCII))

    private fun sriFromBytes(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        // W3C Subresource Integrity uses *standard* base64 (with `+/=`), not base64url.
        return "sha256-" + Base64.encodeToString(digest, Base64.NO_WRAP)
    }
}
