package dev.digitallabor.elpaso.wallet.dcapi

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import dev.digitallabor.elpaso.wallet.domain.model.Credential
import dev.digitallabor.elpaso.wallet.domain.model.Format
import dev.digitallabor.elpaso.wallet.vct.SdJwtClaimsReconstructor
import dev.digitallabor.elpaso.wallet.vct.SdJwtVctExtractor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.MajorType
import org.multipaz.mdoc.issuersigned.IssuerNamespaces
import java.io.ByteArrayOutputStream
import dev.digitallabor.elpaso.wallet.presentation.txdata.TransactionData as UiTransactionData

/**
 * Serialises the wallet's credential set into the `PackageConfig` JSON consumed by the
 * custom DC API matcher (`aptitude_consortium_dcapi_matcher.wasm`).
 *
 * Shape mirrors `crates/aptitude-consortium-dcapi-matcher/src/main.rs::PackageConfig`:
 *
 *   { default_id_prefix, openid4vp, dcql, payment_sca, log_level,
 *     credentials: [ <sd-jwt entry> | <mdoc entry> ] }
 *
 * SD-JWT VC entries carry `vcts` + `holder_binding` + claims-tree of disclosures.
 * mDoc entries carry `doctype` + namespace-keyed `claims` extracted from the issuer-signed
 * payload. The matcher uses `claims` to evaluate DCQL path-existence and value
 * constraints; for mdoc these paths are `[namespace, element_id]`. Values are decoded
 * from CBOR — they only travel into the sandboxed WASM via `ReadCredentialsBuffer`, no
 * broader exposure.
 */
internal object MatcherPackageBuilder {
    private const val LOG_TAG = "MatcherPackageBuilder"

    /**
     * Build the package JSON bytes ready to hand to `CustomMatcherRegistry`.
     *
     * [iconBitmap] is the wallet's launcher icon; the same drawable used by the previous
     * `OpenId4VpRegistry` entries. Passed in (rather than rendered here) so we don't pull
     * an Android Context into this builder.
     */
    fun build(
        credentials: List<Credential>,
        iconBitmap: Bitmap?,
    ): ByteArray {
        val iconB64 = iconBitmap?.let(::bitmapToBase64Png)
        val pkg =
            buildJsonObject {
                put("default_id_prefix", JsonPrimitive("cred-"))
                putJsonObject("openid4vp") {
                    put("enabled", JsonPrimitive(true))
                    putJsonArray("supported_request_protocols") {
                        add(JsonPrimitive("openid4vp-v1-unsigned"))
                        add(JsonPrimitive("openid4vp-v1-signed"))
                        add(JsonPrimitive("openid4vp-v1-multisigned"))
                    }
                    putJsonArray("supported_response_modes") {
                        add(JsonPrimitive("dc_api"))
                        add(JsonPrimitive("dc_api.jwt"))
                    }
                    putJsonArray("supported_response_types") { add(JsonPrimitive("vp_token")) }
                    putJsonArray("supported_query_methods") { add(JsonPrimitive("dcql_query")) }
                    putJsonArray("supported_request_parameters") {
                        add(JsonPrimitive("transaction_data"))
                    }
                }
                putJsonObject("dcql") {
                    put("credential_set_option_mode", JsonPrimitive("first_satisfiable_only"))
                    put("optional_credential_sets_mode", JsonPrimitive("prefer_present"))
                    // `ts12_prefixes` controls which `transaction_data.type` URNs the matcher
                    // routes through its TS12 path (which calls `ts12_payment_summary` and
                    // renders the credential as a payment entry in the system selector).
                    // Default is just `urn:eudi:sca:`; PaSO uses `urn:paso:sca:` so we have to
                    // opt that in explicitly or the credential renders as a plain entry.
                    putJsonArray("ts12_prefixes") {
                        add(JsonPrimitive("urn:eudi:sca:"))
                        add(JsonPrimitive("urn:paso:sca:"))
                    }
                }
                // Map the PaSO TS12 transaction type to its payee/amount payload paths. The
                // matcher uses this to render an `AddPaymentEntry` (merchant + amount) in the
                // system selector rather than a generic verification entry.
                putJsonObject("payment_sca") {
                    putJsonObject(UiTransactionData.PasoPayment.TYPE) {
                        putJsonArray("payee") {
                            add(JsonPrimitive("payload"))
                            add(JsonPrimitive("payee"))
                            add(JsonPrimitive("name"))
                        }
                        putJsonArray("amount") {
                            add(JsonPrimitive("payload"))
                            add(JsonPrimitive("amount"))
                        }
                    }
                }
                put("log_level", JsonPrimitive("info"))
                putJsonArray("credentials") {
                    credentials.forEach { credential ->
                        val entry =
                            when (credential.format) {
                                Format.SdJwtVc -> buildSdJwtEntry(credential, iconB64)
                                Format.MsoMdoc -> buildMdocEntry(credential, iconB64)
                            }
                        if (entry != null) add(entry)
                    }
                }
            }
        return JSON.encodeToString(JsonElement.serializer(), pkg).encodeToByteArray()
    }

    private fun buildSdJwtEntry(
        credential: Credential,
        iconB64: String?,
    ): JsonObject? {
        val vct = SdJwtVctExtractor.extract(credential.payload) ?: return null
        // Reconstructed tree: SD-JWT `_sd` digests resolved to their disclosure values at
        // the position the issuer signed them at. The matcher walks DCQL paths against
        // this tree, so anything that isn't here (or is misplaced relative to where the
        // verifier looks) yields no match.
        val claimsTree = SdJwtClaimsReconstructor.reconstruct(credential.payload)
        // Emit one `fields` entry per leaf path so the system selector can show the value
        // with a sensible label. Nested leaves get dotted display names (`address.locality`)
        // — adequate for the POC; localised metadata-driven labels are a follow-up.
        val leafPaths = collectLeafPaths(claimsTree)
        return buildJsonObject {
            put("id", JsonPrimitive(credential.id))
            put("format", JsonPrimitive(credential.format.wire))
            put("title", JsonPrimitive(credential.displayName))
            put("subtitle", JsonPrimitive(credential.issuerId))
            if (iconB64 != null) put("icon", JsonPrimitive(iconB64))
            putJsonArray("vcts") { add(JsonPrimitive(vct)) }
            put("holder_binding", JsonPrimitive(true))
            putJsonArray("fields") {
                leafPaths.forEach { path ->
                    addJsonObject {
                        putJsonArray("path") { path.forEach { add(JsonPrimitive(it)) } }
                        put("display_name", JsonPrimitive(path.joinToString(".")))
                    }
                }
            }
            put("claims", claimsTree)
            // PaSO TS12 metadata. Surface only on credentials that can actually back a
            // payment binding — for the POC we tag every SD-JWT VC as candidate. The
            // matcher's `can_sign_transaction_data` then enforces payload-shape compatibility
            // against the verifier's `transaction_data` entry.
            putJsonObject("transaction_data_types") {
                putJsonObject(UiTransactionData.PasoPayment.TYPE) {
                    // The TS12 `is_payload_compatible` check rejects payload paths we don't
                    // declare here, and for any *displayable* claim without a `value_type`
                    // it also rejects non-string values. Different PaSO verifiers serialise
                    // `amount` as either a string ("56.66 EUR") or a number (3.88), so we
                    // declare every PaSO payload field without a `display` array — the
                    // claim stays `mandatory` where appropriate, but the strict per-value
                    // type validation is skipped. The actual merchant/amount rendering in
                    // the system selector comes from the package-level `payment_sca`
                    // mapping (string_at_path coerces JSON numbers to strings), so we lose
                    // nothing visible by dropping `display` here.
                    putJsonArray("claims") {
                        addJsonObject {
                            putJsonArray("path") { add(JsonPrimitive("amount")) }
                            put("mandatory", JsonPrimitive(true))
                        }
                        addJsonObject {
                            putJsonArray("path") {
                                add(JsonPrimitive("payee"))
                                add(JsonPrimitive("name"))
                            }
                            put("mandatory", JsonPrimitive(true))
                        }
                        // Optional PaSO payload fields (path-allowlisted, no validation).
                        addJsonObject {
                            putJsonArray("path") { add(JsonPrimitive("transaction_id")) }
                            put("mandatory", JsonPrimitive(false))
                        }
                        addJsonObject {
                            putJsonArray("path") {
                                add(JsonPrimitive("payee"))
                                add(JsonPrimitive("id"))
                            }
                            put("mandatory", JsonPrimitive(false))
                        }
                        addJsonObject {
                            putJsonArray("path") { add(JsonPrimitive("additional_info")) }
                            put("mandatory", JsonPrimitive(false))
                        }
                        addJsonObject {
                            putJsonArray("path") { add(JsonPrimitive("currency")) }
                            put("mandatory", JsonPrimitive(false))
                        }
                    }
                }
            }
        }
    }

    /**
     * Depth-first walk of the reconstructed claims tree, yielding the path to every
     * scalar/array leaf. Used to populate `fields` for display in the system selector.
     * Empty objects/arrays are skipped because there's nothing to label.
     */
    private fun collectLeafPaths(root: JsonObject): List<List<String>> {
        val out = mutableListOf<List<String>>()

        fun walk(
            element: JsonElement,
            prefix: List<String>,
        ) {
            when (element) {
                is JsonObject -> {
                    if (element.isEmpty()) {
                        if (prefix.isNotEmpty()) out += prefix
                        return
                    }
                    for ((key, value) in element) walk(value, prefix + key)
                }

                is JsonArray -> {
                    // Treat arrays as leaves — the matcher's `select_nodes` resolves an
                    // array path to the full array, and we have no useful per-index label.
                    if (prefix.isNotEmpty()) out += prefix
                }

                is JsonPrimitive -> {
                    if (prefix.isNotEmpty()) out += prefix
                }

                else -> {
                    if (prefix.isNotEmpty()) out += prefix
                }
            }
        }
        walk(root, emptyList())
        return out
    }

    /**
     * Build a package entry for an mdoc credential. Decodes the `IssuerSigned` CBOR
     * (`{ nameSpaces, issuerAuth }`) and extracts the namespace-keyed element values so
     * the matcher's DCQL evaluator can resolve paths of the form `[namespace, element_id]`
     * — the standard mdoc claim path shape per OID4VP §6.4.1.
     *
     * `doctype` is read from `credential.configurationId` (the issuance flow stores the
     * mdoc `docType` there). Element values are converted to plain JSON via CBOR major
     * type (text → string, ints → number, bool/null → primitive, bstr → base64); date
     * tags and similar complex tags are flattened to whatever the tagged inner item is.
     */
    private fun buildMdocEntry(
        credential: Credential,
        iconB64: String?,
    ): JsonObject? =
        runCatching {
            val payloadBytes =
                Base64.decode(
                    credential.payload.decodeToString(),
                    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
                )
            val issuerSignedMap = Cbor.decode(payloadBytes).asMap
            val nameSpacesItem =
                issuerSignedMap.entries.firstOrNull { it.key.asTstr == "nameSpaces" }?.value
                    ?: run {
                        Log.w(LOG_TAG, "buildMdocEntry: no nameSpaces in IssuerSigned for ${credential.id}")
                        return@runCatching null
                    }
            // Strict on purpose. Unlike the detail screen (see CredentialClaims.displayClaimFrom,
            // which tolerates a non-`bstr` IssuerSignedItem.random for display only), the matcher
            // must not advertise a credential the presentation path cannot actually produce a
            // DeviceResponse for — MdocDeviceResponseBuilder re-encodes each item and the MSO
            // digests are over the issuer's original bytes. A non-conformant issuer therefore
            // yields a logged omission here rather than an entry that fails at selection time.
            val namespaces = IssuerNamespaces.fromDataItem(nameSpacesItem)
            if (namespaces.data.isEmpty()) {
                Log.w(LOG_TAG, "buildMdocEntry: IssuerNamespaces empty for ${credential.id}")
                return@runCatching null
            }

            val claims =
                buildJsonObject {
                    for ((namespaceName, elementsByName) in namespaces.data) {
                        putJsonObject(namespaceName) {
                            for ((elementName, signedItem) in elementsByName) {
                                put(elementName, cborToJson(signedItem.dataElementValue))
                            }
                        }
                    }
                }
            val leafPaths = mutableListOf<List<String>>()
            for ((namespaceName, elementsByName) in namespaces.data) {
                for (elementName in elementsByName.keys) {
                    leafPaths += listOf(namespaceName, elementName)
                }
            }

            buildJsonObject {
                put("id", JsonPrimitive(credential.id))
                put("format", JsonPrimitive(credential.format.wire))
                put("title", JsonPrimitive(credential.displayName))
                put("subtitle", JsonPrimitive(credential.issuerId))
                if (iconB64 != null) put("icon", JsonPrimitive(iconB64))
                put("doctype", JsonPrimitive(credential.configurationId))
                putJsonArray("fields") {
                    leafPaths.forEach { path ->
                        addJsonObject {
                            putJsonArray("path") { path.forEach { add(JsonPrimitive(it)) } }
                            put("display_name", JsonPrimitive(path.joinToString(".")))
                        }
                    }
                }
                put("claims", claims)
            }
        }.onFailure { Log.w(LOG_TAG, "buildMdocEntry failed for ${credential.id}", it) }.getOrNull()

    /**
     * Convert a CBOR [DataItem] to a JSON element for the matcher's `claims` tree. The
     * matcher only inspects values for DCQL `values:`/`is_present` checks (rare in
     * practice — most queries are path existence), so lossy conversions are fine: byte
     * strings become base64, tagged items unwrap to their inner data item, floats become
     * numbers. Anything unrecognised becomes JSON `null` rather than throwing — the
     * matcher will still see the path exist, which is what most queries actually need.
     */
    private fun cborToJson(item: DataItem): JsonElement =
        when (item.majorType) {
            MajorType.UNSIGNED_INTEGER, MajorType.NEGATIVE_INTEGER -> {
                JsonPrimitive(item.asNumber)
            }

            MajorType.BYTE_STRING -> {
                JsonPrimitive(Base64.encodeToString(item.asBstr, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING))
            }

            MajorType.UNICODE_STRING -> {
                JsonPrimitive(item.asTstr)
            }

            MajorType.ARRAY -> {
                JsonArray(item.asArray.map(::cborToJson))
            }

            MajorType.MAP -> {
                buildJsonObject {
                    for ((k, v) in item.asMap) {
                        val key =
                            when (k.majorType) {
                                MajorType.UNICODE_STRING -> k.asTstr
                                MajorType.UNSIGNED_INTEGER, MajorType.NEGATIVE_INTEGER -> k.asNumber.toString()
                                else -> k.toString()
                            }
                        put(key, cborToJson(v))
                    }
                }
            }

            MajorType.TAG -> {
                // Unwrap tagged values (e.g. dates tag 0/1004, embedded CBOR tag 24). For the
                // matcher's purposes the inner value is what's interesting.
                runCatching { cborToJson(Cbor.decode(Cbor.encode(item)).asTagged) }.getOrElse { JsonNull }
            }

            MajorType.SPECIAL -> {
                when {
                    item.toString() == "true" -> JsonPrimitive(true)
                    item.toString() == "false" -> JsonPrimitive(false)
                    else -> runCatching { JsonPrimitive(item.asBoolean) }.getOrElse { JsonNull }
                }
            }
        }

    private fun bitmapToBase64Png(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return Base64.encodeToString(
            stream.toByteArray(),
            Base64.NO_WRAP,
        )
    }

    private val JSON = Json { encodeDefaults = false }
}
