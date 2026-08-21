package dev.digitallabor.elpaso.wallet.issuance

import dev.digitallabor.elpaso.wallet.data.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/**
 * Implements the `credential_metadata_uri` OID4VCI extension defined in
 * paso-proof-metadata.md §2.
 *
 * - [discoverUri] reads the issuer's `/.well-known/openid-credential-issuer` raw JSON
 *   and pulls `credential_configurations_supported[<id>].credential_metadata_uri`.
 *   The wired `eudi-openid4vci-kt 0.11.0` library doesn't expose this extension
 *   field as a typed property, so we read the unstructured document ourselves.
 * - [fetchJwt] performs the actual GET with content-negotiation. We request
 *   `application/jwt`; the issuer SHALL include at least the first preferred
 *   locale per spec §2 and MAY include more.
 */
class CredentialMetadataClient(
    private val httpClient: HttpClient,
) {

    data class FetchedMetadataJwt(val rawJwt: String, val metadataUri: String)

    /**
     * Discovers a `credential_metadata_uri` for the given credential. The wallet
     * stores the credential's *type identifier* (vct for SD-JWT VC, doctype for
     * mso_mdoc) in `Credential.configurationId`, NOT the OID4VCI configuration
     * identifier used as a map key in `credential_configurations_supported`.
     * Iterate every configuration in the issuer metadata and return the
     * `credential_metadata_uri` of the one whose own `vct` or `doctype` field
     * equals [credentialTypeIdentifier].
     */
    suspend fun discoverUri(issuerId: String, credentialTypeIdentifier: String): Result<String?> = runCatching {
        for (candidate in wellKnownCandidates(issuerId)) {
            val response = runCatching {
                httpClient.get(candidate) { accept(ContentType.Application.Json) }
            }.getOrNull() ?: continue
            if (!response.status.isSuccess()) continue
            val body = response.bodyAsText()
            val root = runCatching { HttpClientFactory.json.parseToJsonElement(body).jsonObject }
                .getOrNull() ?: continue
            val configs = root["credential_configurations_supported"] as? JsonObject ?: continue
            val matching = configs.values
                .filterIsInstance<JsonObject>()
                .firstOrNull { cfg ->
                    val vct = cfg["vct"]?.jsonPrimitive?.content
                    val doctype = cfg["doctype"]?.jsonPrimitive?.content
                    vct == credentialTypeIdentifier || doctype == credentialTypeIdentifier
                } ?: continue
            val uri = matching["credential_metadata_uri"]?.jsonPrimitive?.content
            if (uri != null) return@runCatching uri
        }
        null
    }

    /**
     * The wallet tries two well-known URL transforms because issuers split on this:
     * (a) **suffix** — `<issuer>/.well-known/openid-credential-issuer`, the form
     *     OID4VCI 1.0 §11.2.2 references. Works when the issuer ID has no path.
     * (b) **path-aware** per RFC 8414 — `<host>/.well-known/openid-credential-issuer<path>`.
     *     Eudiplo, EUDI ref, and any issuer hosting multiple tenants under one origin
     *     use this form.
     */
    private fun wellKnownCandidates(issuerId: String): List<String> {
        val trimmed = issuerId.trimEnd('/')
        val candidates = mutableListOf(trimmed + WELL_KNOWN)
        val idx = trimmed.indexOf("//").let { if (it < 0) return candidates else it + 2 }
        val pathStart = trimmed.indexOf('/', idx)
        if (pathStart > 0) {
            val origin = trimmed.substring(0, pathStart)
            val path = trimmed.substring(pathStart)
            candidates += origin + WELL_KNOWN + path
        }
        return candidates
    }

    suspend fun fetchJwt(uri: String, locale: Locale): Result<FetchedMetadataJwt> = runCatching {
        val tag = locale.toLanguageTag()
        val acceptLanguage = if (tag.equals("en", ignoreCase = true)) "en" else "$tag, en;q=0.8"
        val response = httpClient.get(uri) {
            headers {
                append(HttpHeaders.Accept, "application/jwt")
                append(HttpHeaders.AcceptLanguage, acceptLanguage)
            }
        }
        check(response.status.isSuccess()) {
            "credential_metadata_uri returned ${response.status} for $uri"
        }
        val contentType = response.contentType()
        check(contentType == null || contentType.match(JWT_CONTENT_TYPE)) {
            "credential_metadata_uri returned $contentType for $uri (expected application/jwt)"
        }
        val body = response.bodyAsText().trim()
        check(body.isNotEmpty()) { "credential_metadata_uri returned empty body for $uri" }
        FetchedMetadataJwt(rawJwt = body, metadataUri = uri)
    }

    companion object {
        private val JWT_CONTENT_TYPE = ContentType("application", "jwt")
        private const val WELL_KNOWN = "/.well-known/openid-credential-issuer"
    }
}
