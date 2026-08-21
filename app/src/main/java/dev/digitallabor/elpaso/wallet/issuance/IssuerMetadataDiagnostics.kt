package dev.digitallabor.elpaso.wallet.issuance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Explains *why* `eudi-lib-jvm-openid4vci-kt` refused a Credential Issuer metadata document.
 *
 * The library parses `credential_configurations_supported` eagerly as one unit, so a single
 * malformed entry fails the whole document and takes every other credential from that issuer with
 * it. Worse, the resulting `JsonDecodingException` reports only a JSON-path fragment (`$.0`) and
 * never names the offending configuration — which is what makes this class of bug expensive to
 * diagnose from a wallet-side error message alone.
 *
 * [diagnose] walks the same document by hand and reports *every* offender with its configuration
 * id.
 *
 * Strictly diagnostic: findings feed log lines and the error modal's technical details, never a
 * protocol decision. Nothing here repairs or rewrites what an issuer sent — a non-conformant
 * issuer still fails, it just fails legibly.
 *
 * Free of Android dependencies so it is unit-testable on the JVM, where
 * `testOptions.unitTests.isReturnDefaultValues` stubs out `android.util.*`.
 */
object IssuerMetadataDiagnostics {
    /** A single OpenID4VCI 1.0 shape violation, attributed to the configuration that has it. */
    data class Finding(
        val configId: String,
        val field: String,
        val problem: String,
    ) {
        fun render(): String = "config '$configId': $field $problem"
    }

    /**
     * Every shape violation in [json], or an empty list when the document is unparseable or has
     * nothing to report — a diagnostic must never throw on top of the failure it describes.
     */
    fun diagnose(json: String): List<Finding> =
        runCatching {
            val configurations =
                Json
                    .parseToJsonElement(json)
                    .jsonObject[CONFIGURATIONS_SUPPORTED]
                    ?.jsonObject
                    ?: return@runCatching emptyList()

            configurations.entries.flatMap { (configId, element) ->
                when (val configuration = element as? JsonObject) {
                    null -> listOf(Finding(configId, CONFIGURATIONS_SUPPORTED, "must be a JSON object"))
                    else -> diagnoseConfiguration(configId, configuration)
                }
            }
        }.getOrDefault(emptyList())

    private fun diagnoseConfiguration(
        configId: String,
        configuration: JsonObject,
    ): List<Finding> {
        val format = configuration.stringOrNull(FORMAT)

        // An unrecognised format makes every other check meaningless — the required fields and the
        // algorithm value space are both format-specific. Report just that and stop.
        if (format != FORMAT_MSO_MDOC && format != FORMAT_SD_JWT_VC) {
            return listOf(
                Finding(
                    configId,
                    FORMAT,
                    "is ${format?.let { "'$it'" } ?: "missing"}; this wallet supports " +
                        "'$FORMAT_MSO_MDOC' and '$FORMAT_SD_JWT_VC'",
                ),
            )
        }

        return buildList {
            typeIdentifierFinding(configId, configuration, format)?.let(::add)
            signingAlgorithmFinding(configId, configuration, format)?.let(::add)
        }
    }

    /** `doctype` identifies an mdoc type, `vct` an SD-JWT VC type. Each is required. */
    private fun typeIdentifierFinding(
        configId: String,
        configuration: JsonObject,
        format: String,
    ): Finding? {
        val field = if (format == FORMAT_MSO_MDOC) DOCTYPE else VCT
        return if (!configuration.stringOrNull(field).isNullOrBlank()) {
            null
        } else {
            Finding(configId, field, "is required for format '$format' but is missing")
        }
    }

    /**
     * `credential_signing_alg_values_supported` has a format-dependent value space, and this is
     * the mismatch real issuers get wrong: mdocs are COSE-signed, so the values are COSE algorithm
     * identifiers (integers, e.g. `-7` for ES256), whereas SD-JWT VCs are JOSE-signed and use
     * algorithm *names* (strings, e.g. `"ES256"`).
     */
    private fun signingAlgorithmFinding(
        configId: String,
        configuration: JsonObject,
        format: String,
    ): Finding? {
        val declared = configuration[SIGNING_ALGS] ?: return null // optional; absence is no defect
        val values =
            declared as? JsonArray
                ?: return Finding(configId, SIGNING_ALGS, "must be a JSON array")

        val primitives = values.filterIsInstance<JsonPrimitive>()
        val strings = primitives.filter { it.isString }
        val integers = primitives.filter { !it.isString && it.intOrNull != null }

        return when {
            format == FORMAT_MSO_MDOC && strings.isNotEmpty() -> {
                Finding(
                    configId,
                    SIGNING_ALGS,
                    "must be COSE algorithm integers for format '$FORMAT_MSO_MDOC' " +
                        "(e.g. -7 for ES256), but got string(s) " +
                        strings.joinToString(prefix = "[", postfix = "]") { "\"${it.content}\"" },
                )
            }

            format == FORMAT_SD_JWT_VC && integers.isNotEmpty() -> {
                Finding(
                    configId,
                    SIGNING_ALGS,
                    "must be JOSE algorithm names for format '$FORMAT_SD_JWT_VC' " +
                        "(e.g. \"ES256\"), but got integer(s) " +
                        integers.joinToString(prefix = "[", postfix = "]") { it.content },
                )
            }

            else -> {
                null
            }
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private const val CONFIGURATIONS_SUPPORTED = "credential_configurations_supported"
    private const val SIGNING_ALGS = "credential_signing_alg_values_supported"
    private const val FORMAT = "format"
    private const val DOCTYPE = "doctype"
    private const val VCT = "vct"

    // Mirrors eu.europa.ec.eudi.openid4vci.FORMAT_MSO_MDOC / FORMAT_SD_JWT_VC. Duplicated as plain
    // strings so this file stays a dependency-free, JVM-testable helper.
    private const val FORMAT_MSO_MDOC = "mso_mdoc"
    private const val FORMAT_SD_JWT_VC = "dc+sd-jwt"
}
