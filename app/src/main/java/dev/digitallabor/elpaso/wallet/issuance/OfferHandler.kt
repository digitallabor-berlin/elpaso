package dev.digitallabor.elpaso.wallet.issuance

import android.net.Uri

/**
 * Parses incoming `openid-credential-offer://`, `haip://`, or `https://wallet.example.com/cb?...`
 * URIs into a normalised string the EUDI VCI library accepts.
 *
 * The library handles both by-value (`credential_offer=...`) and by-reference
 * (`credential_offer_uri=...`) forms; we just pass the original URI through.
 */
object OfferHandler {
    fun parse(uri: Uri): String? {
        val raw = uri.toString()
        return when {
            uri.scheme == "openid-credential-offer" || uri.scheme == "haip" -> raw
            uri.scheme == "https" -> raw
            else -> null
        }
    }

    fun extractIssuer(uri: Uri): String? = runCatching {
        val byValue = uri.getQueryParameter("credential_offer")
        val byRef = uri.getQueryParameter("credential_offer_uri")
        when {
            byValue != null -> Regex("\"credential_issuer\"\\s*:\\s*\"([^\"]+)\"")
                .find(byValue)?.groupValues?.get(1)
            byRef != null -> Uri.parse(byRef).host
            else -> null
        }
    }.getOrNull()
}
