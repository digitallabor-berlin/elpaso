package dev.digitallabor.elpaso.wallet.dcapi

import androidx.credentials.registry.digitalcredentials.mdoc.MdocField
import androidx.credentials.registry.digitalcredentials.sdjwt.SdJwtClaim
import androidx.credentials.registry.provider.digitalcredentials.VerificationFieldDisplayProperties
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure mapping from decoded credential content to the androidx registry field types.
 *
 * Split out of [DcRegistryBlobBuilder] on purpose: entry-level construction needs a real
 * `Bitmap` for the icon, and this module has no `androidTest` source set and no
 * Robolectric, so anything touching `Bitmap` cannot be unit-tested here. Everything in
 * this file is deliberately free of Android types so it can be.
 *
 * The shapes produced here are consumed by the vendored CMWallet matcher, whose expected
 * registry layout is documented in `matcher/upstream/index.md`: for `dc+sd-jwt`, claims
 * nest into a `paths` tree keyed by each path segment, with `{value, display}` leaves;
 * for `mso_mdoc`, `paths` is namespace then element identifier.
 */
internal object DcRegistryEntryMapper {
    /**
     * Depth-first walk of a reconstructed SD-JWT claims tree, yielding one [SdJwtClaim]
     * per leaf.
     *
     * Arrays are leaves, not branches: the matcher's path resolution returns the whole
     * array for an array-valued path, and there is no useful per-index display label.
     * Empty objects are also leaves so the path still exists for DCQL path-existence
     * checks, which is what most queries actually test.
     */
    fun sdJwtClaims(claimsTree: JsonObject): List<SdJwtClaim> {
        val out = mutableListOf<SdJwtClaim>()

        fun walk(
            element: JsonElement,
            prefix: List<String>,
        ) {
            when {
                element is JsonObject && element.isNotEmpty() -> {
                    element.forEach { (key, value) -> walk(value, prefix + key) }
                }

                prefix.isEmpty() -> {
                    Unit
                }

                // never emit a claim for the tree root

                else -> {
                    out += claim(prefix, element)
                }
            }
        }

        walk(claimsTree, emptyList())
        return out
    }

    /** One [MdocField] per element, addressed as `[namespace, element_id]` per OID4VP 6.4.1. */
    fun mdocFields(namespaces: Map<String, Map<String, JsonElement>>): List<MdocField> =
        namespaces.flatMap { (namespace, elements) ->
            elements.map { (identifier, value) ->
                MdocField(
                    namespace = namespace,
                    identifier = identifier,
                    fieldValue = unwrap(value),
                    fieldDisplayPropertySet =
                        setOf(
                            VerificationFieldDisplayProperties(
                                displayName = identifier,
                                displayValue = displayValue(value) ?: "",
                            ),
                        ),
                )
            }
        }

    /**
     * Label for a nested claim. Dotted segments (`address.locality`) are adequate for
     * now; localised, VCT-metadata-driven labels are a separate piece of work.
     */
    fun displayName(path: List<String>): String = path.joinToString(".")

    /** Human-readable rendering of a claim value, or null when there is nothing to show. */
    fun displayValue(element: JsonElement): String? =
        when (element) {
            is JsonPrimitive -> if (element is JsonNull) null else element.content
            is JsonObject, is JsonArray -> element.toString()
        }

    private fun claim(
        path: List<String>,
        element: JsonElement,
    ) = SdJwtClaim(
        path = path,
        value = unwrap(element),
        fieldDisplayPropertySet =
            setOf(
                VerificationFieldDisplayProperties(
                    displayName = displayName(path),
                    displayValue = displayValue(element) ?: "",
                ),
            ),
        isSelectivelyDisclosable = true,
    )

    /**
     * Convert a [JsonElement] to the plain Kotlin value the serialiser writes into the
     * blob's `value` position. Strings, booleans and numbers map natively; containers
     * fall back to their JSON text, because the matcher only inspects values for DCQL
     * `values:` equality checks and a stringified container is closer to useful than a
     * dropped path.
     */
    private fun unwrap(element: JsonElement): Any =
        when (element) {
            is JsonPrimitive -> {
                when {
                    element is JsonNull -> {
                        ""
                    }

                    element.isString -> {
                        element.content
                    }

                    element.content == "true" -> {
                        true
                    }

                    element.content == "false" -> {
                        false
                    }

                    else -> {
                        element.content.toLongOrNull()
                            ?: element.content.toDoubleOrNull()
                            ?: element.content
                    }
                }
            }

            is JsonObject, is JsonArray -> {
                element.toString()
            }
        }
}
