package dev.digitallabor.elpaso.wallet.vct

import dev.digitallabor.elpaso.wallet.util.B64u
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Decodes the issuer-signed JWT segment of an SD-JWT VC payload and returns the `vct`
 * claim. `vct` carries the credential type identifier — for HTTPS-scheme VCTs this is
 * also the URL where the Type Metadata document is published.
 */
object SdJwtVctExtractor {
    fun extract(payload: ByteArray): String? = runCatching {
        val sdJwt = payload.decodeToString()
        val issuerJwt = sdJwt.substringBefore('~')
        val parts = issuerJwt.split('.')
        if (parts.size < 2) return@runCatching null
        val payloadJson = B64u.decode(parts[1]).decodeToString()
        Json.parseToJsonElement(payloadJson).jsonObject["vct"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
}
