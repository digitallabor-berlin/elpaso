package dev.digitallabor.elpaso.wallet.domain.claims

import dev.digitallabor.elpaso.wallet.domain.model.Credential
import dev.digitallabor.elpaso.wallet.domain.model.Format
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * Decodes a stored credential's payload into the claim trees the detail screen
 * renders. SD-JWT-VC is fully supported (JWT-payload + flat-merge of disclosures);
 * mDoc returns empty objects until Multipaz CBOR decoding is wired up.
 *
 * Split into [Extracted.user] (the application claims users care about) and
 * [Extracted.protocol] (the JWT scaffolding — including timestamps like `iat` /
 * `exp` / `nbf` — shown in the collapsible "Technical details" section).
 */
object CredentialClaims {

    data class Extracted(val user: JsonObject, val protocol: JsonObject) {
        companion object {
            val Empty = Extracted(JsonObject(emptyMap()), JsonObject(emptyMap()))
        }
    }

    private val SD_JWT_PROTOCOL_KEYS = setOf(
        "_sd", "_sd_alg", "cnf", "iss", "iat", "exp", "nbf", "jti", "vct", "status",
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val urlDecoder: Base64.Decoder = Base64.getUrlDecoder()

    fun extract(credential: Credential): Extracted = when (credential.format) {
        Format.SdJwtVc -> extractSdJwtVc(credential.payload)
        Format.MsoMdoc -> extractMsoMdoc(credential)
    }

    private fun extractMsoMdoc(credential: Credential): Extracted = runCatching {
        val decoded = decodeBase64Url(credential.payload.decodeToString()) ?: return@runCatching Extracted.Empty
        
        // EUDI OpenID4VCI issues mso_mdoc as IssuerSigned CBOR
        val issuerSignedItem = org.multipaz.cbor.Cbor.decode(decoded)
        val issuerSignedMap = issuerSignedItem.asMap
        val nameSpacesDataItem = issuerSignedMap.entries.firstOrNull { it.key.asTstr == "nameSpaces" }?.value ?: return@runCatching Extracted.Empty
        
        val namespaces = org.multipaz.mdoc.issuersigned.IssuerNamespaces.Companion.fromDataItem(nameSpacesDataItem)
        
        val userClaims = buildJsonObject {
            for ((namespace, items) in namespaces.data) {
                for ((elementIdentifier, item) in items) {
                    put(item.dataElementIdentifier, cborToJson(item.dataElementValue))
                }
            }
        }
        val protocolClaims = buildJsonObject {
            put("docType", kotlinx.serialization.json.JsonPrimitive(credential.configurationId))
        }
        Extracted(userClaims, protocolClaims)
    }.onFailure { println("Failed to extract mdoc: $it") }.getOrDefault(Extracted.Empty)

    private fun cborToJson(item: org.multipaz.cbor.DataItem): JsonElement = when (item.majorType) {
        org.multipaz.cbor.MajorType.UNSIGNED_INTEGER,
        org.multipaz.cbor.MajorType.NEGATIVE_INTEGER -> kotlinx.serialization.json.JsonPrimitive(item.asNumber)
        org.multipaz.cbor.MajorType.BYTE_STRING -> kotlinx.serialization.json.JsonPrimitive(Base64.getEncoder().encodeToString(item.asBstr))
        org.multipaz.cbor.MajorType.UNICODE_STRING -> kotlinx.serialization.json.JsonPrimitive(item.asTstr)
        org.multipaz.cbor.MajorType.ARRAY -> buildJsonArray {
            for (child in item.asArray) {
                add(cborToJson(child))
            }
        }
        org.multipaz.cbor.MajorType.MAP -> buildJsonObject {
            for ((k, v) in item.asMap) {
                put(k.asTstr, cborToJson(v))
            }
        }
        org.multipaz.cbor.MajorType.TAG -> cborToJson(item.asTagged)
        org.multipaz.cbor.MajorType.SPECIAL -> {
            runCatching { kotlinx.serialization.json.JsonPrimitive(item.asBoolean) }
                .recoverCatching { kotlinx.serialization.json.JsonPrimitive(item.asDouble) }
                .getOrDefault(kotlinx.serialization.json.JsonNull)
        }
        else -> kotlinx.serialization.json.JsonNull
    }

    private fun extractSdJwtVc(payload: ByteArray): Extracted = runCatching {
        val sdJwt = payload.decodeToString()
        val segments = sdJwt.split('~')
        val issuerJwt = segments.firstOrNull() ?: return@runCatching Extracted.Empty
        val jwtParts = issuerJwt.split('.')
        if (jwtParts.size < 2) return@runCatching Extracted.Empty

        val payloadJson = decodeBase64Url(jwtParts[1])?.decodeToString()
            ?: return@runCatching Extracted.Empty
        val jwtPayload = json.parseToJsonElement(payloadJson).jsonObject

        val disclosed = segments.drop(1)
            .filter { it.isNotBlank() }
            .mapNotNull(::parseObjectDisclosure)

        val userClaims = buildJsonObject {
            jwtPayload.filterKeys { it !in SD_JWT_PROTOCOL_KEYS }.forEach { (k, v) -> put(k, v) }
            // Disclosures override JWT-payload keys when names collide; in practice
            // SD-JWT issuers don't both publish the visible value and disclose it.
            disclosed.forEach { (k, v) -> put(k, v) }
        }
        val protocolClaims = buildJsonObject {
            jwtPayload.filterKeys { it in SD_JWT_PROTOCOL_KEYS }.forEach { (k, v) -> put(k, v) }
        }
        Extracted(userClaims, protocolClaims)
    }.getOrDefault(Extracted.Empty)

    /**
     * Returns the (name, value) pair for an object-property disclosure
     * (`[salt, name, value]`). Array-element disclosures (`[salt, value]`) have no
     * label and are skipped — the detail screen renders entries by name.
     */
    private fun parseObjectDisclosure(disclosure: String): Pair<String, JsonElement>? = runCatching {
        val decoded = decodeBase64Url(disclosure)?.decodeToString() ?: return@runCatching null
        val arr = json.parseToJsonElement(decoded).jsonArray
        if (arr.size != 3) return@runCatching null
        val name = arr[1].jsonPrimitive.content
        name to arr[2]
    }.getOrNull()

    private fun decodeBase64Url(s: String): ByteArray? = runCatching {
        urlDecoder.decode(s)
    }.getOrNull()
}
