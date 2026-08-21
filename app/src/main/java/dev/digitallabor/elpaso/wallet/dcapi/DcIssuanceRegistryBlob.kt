package dev.digitallabor.elpaso.wallet.dcapi

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds the creation-options payload consumed by the vendored issuance matcher
 * (`assets/dc_issuance_matcher.wasm`).
 *
 * Binary layout, matching what the matcher's `main()` expects:
 *
 * ```text
 * [0, 4)                     little-endian int32: byte offset of the JSON
 * [4, 4 + iconPng.size)      the entry icon, PNG-encoded
 * [4 + iconPng.size, end)    UTF-8 JSON
 * ```
 *
 * The matcher shows an entry when `capabilities` is absent, or when it contains a key equal
 * to the request's `credential_issuer`. Omitting the key therefore means "any issuer".
 *
 * Pure Kotlin so the offset arithmetic is unit-testable — an off-by-four here surfaces only
 * as a silently broken icon in the system sheet.
 */
object DcIssuanceRegistryBlob {
    fun build(
        iconPng: ByteArray,
        title: String,
        subtitle: String?,
        issuerAllowlist: List<String>?,
    ): ByteArray {
        val json =
            buildJsonObject {
                putJsonObject("display") {
                    put("title", title)
                    if (subtitle != null) put("subtitle", subtitle)
                    putJsonObject("icon") {
                        put("start", ICON_OFFSET)
                        put("length", iconPng.size)
                    }
                }
                if (issuerAllowlist != null) {
                    putJsonObject("capabilities") {
                        issuerAllowlist.forEach { issuerId -> putJsonObject(issuerId) {} }
                    }
                }
            }

        val out = ByteArrayOutputStream()
        out.write(
            ByteBuffer
                .allocate(HEADER_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(ICON_OFFSET + iconPng.size)
                .array(),
        )
        out.write(iconPng)
        out.write(json.toString().toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    private const val HEADER_BYTES = 4
    private const val ICON_OFFSET = HEADER_BYTES
}
