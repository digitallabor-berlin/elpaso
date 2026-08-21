package dev.digitallabor.elpaso.wallet.vct

import dev.digitallabor.elpaso.wallet.util.B64u
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Scans an SD-JWT VC payload for its top-level disclosable claim names.
 *
 * Each disclosure is `base64url(JSON([salt, name, value]))` for an object property or
 * `base64url(JSON([salt, value]))` for an array element. We surface only the named
 * disclosures — that's what registry matching (and the existing DCQL matcher) keys off.
 */
object SdJwtDisclosureScanner {

    data class Disclosure(val name: String, val value: Any?)

    fun scan(payload: ByteArray): List<Disclosure> = runCatching {
        val sdJwt = payload.decodeToString()
        val parts = sdJwt.split('~').filter { it.isNotEmpty() }
        if (parts.size < 2) return@runCatching emptyList()
        parts.drop(1).mapNotNull { decode(it) }
    }.getOrDefault(emptyList())

    private fun decode(disclosure: String): Disclosure? = runCatching {
        val json = B64u.decode(disclosure).decodeToString()
        val arr = Json.parseToJsonElement(json) as? JsonArray ?: return@runCatching null
        // Object-property disclosure: [salt, name, value]
        if (arr.size == 3) {
            val name = (arr[1] as? JsonPrimitive)?.contentOrNull ?: return@runCatching null
            Disclosure(name, arr[2])
        } else null
    }.getOrNull()
}
