package dev.digitallabor.elpaso.wallet.data.trust

import android.util.Log
import dev.digitallabor.elpaso.wallet.data.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** The §5.2 configuration document. `jwks` stays a raw object so Nimbus parses it. */
@Serializable
internal data class JwtVcIssuerMetadataDto(
    val issuer: String,
    val jwks: JsonObject? = null,
    val jwks_uri: String? = null,
)

/**
 * Resolves an issuer's JWK Set through the JWT VC Issuer Metadata mechanism of
 * draft-ietf-oauth-sd-jwt-vc-11 §5.
 *
 * Every failure is a `Result.failure` and **never** a fallback to the X.509 mechanism.
 * That is §10.2 — "for any given `iss` value, an attacker cannot influence the type of
 * verification method" — and it is enforced structurally: this class knows nothing
 * about certificates, so there is no code path here that could degrade to one.
 *
 * The request is time- and size-bound per §10.1. The 5 s per-request timeout overrides
 * the 20 s global `requestTimeoutMillis` in `HttpClientFactory`; the body is read in one
 * bounded pull and rejected if it exceeds [MAX_BODY_BYTES] rather than being buffered
 * whole. A `jwks_uri` is an independently attacker-influenced value and so gets its own
 * pass through [WellKnownUrlGuard] before it is requested.
 */
class JwtVcIssuerMetadataClient(
    private val httpClient: HttpClient,
    private val hostResolver: HostResolver = WellKnownUrlGuard.SYSTEM_RESOLVER,
) {
    suspend fun fetch(
        iss: String,
        now: Instant = Instant.now(),
    ): Result<IssuerKeySet> =
        runCatching {
            val wellKnownUrl = JwtVcIssuerUrl.of(iss).getOrThrow()
            val documentJson = getGuarded(wellKnownUrl)

            val dto =
                HttpClientFactory.json.decodeFromString(
                    JwtVcIssuerMetadataDto.serializer(),
                    documentJson,
                )
            validateDocument(dto, iss)

            val (jwksJson, sourceUrl) =
                if (dto.jwks != null) {
                    dto.jwks.toString() to wellKnownUrl
                } else {
                    val jwksUri = requireNotNull(dto.jwks_uri)
                    getGuarded(jwksUri) to jwksUri
                }

            IssuerKeySet.parse(
                issuer = dto.issuer,
                jwksJson = jwksJson,
                sourceUrl = sourceUrl,
                fetchedAt = now,
            )
        }.onFailure {
            Log.w(LOG_TAG, "jwt-vc-issuer resolution failed for $iss", it)
        }

    /** Guard, request, bound. Every network read in this class goes through here. */
    private suspend fun getGuarded(url: String): String {
        WellKnownUrlGuard.check(url, hostResolver).getOrThrow()
        val response: HttpResponse =
            httpClient.get(url) {
                timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
            }
        check(response.status.isSuccess()) { "GET $url returned ${response.status.value}" }
        val contentType = response.contentType()
        check(contentType != null && contentType.match(ContentType.Application.Json)) {
            "GET $url returned content type $contentType; §5.2 requires application/json"
        }
        return readBounded(response, url)
    }

    /**
     * Reads at most [MAX_BODY_BYTES] + 1 bytes and rejects when the extra byte arrived,
     * so an unbounded response is refused rather than accumulated.
     */
    private suspend fun readBounded(
        response: HttpResponse,
        url: String,
    ): String {
        val bytes =
            response
                .bodyAsChannel()
                .readRemaining((MAX_BODY_BYTES + 1).toLong())
                .readByteArray()
        check(bytes.size <= MAX_BODY_BYTES) { "GET $url body exceeds $MAX_BODY_BYTES bytes" }
        return bytes.decodeToString()
    }

    companion object {
        private const val LOG_TAG = "JwtVcIssuerMeta"

        /** §10.1 "size-bound". 64 KiB is far above any real JWK Set. */
        const val MAX_BODY_BYTES: Int = 64 * 1024

        /** §10.1 "time-bound". Tighter than the 20 s global default. */
        const val REQUEST_TIMEOUT_MS: Long = 5_000

        /**
         * §5.2 and §5.3, pure over the decoded document so the rules are testable
         * without an engine. Throws [IllegalStateException] naming the violated rule.
         */
        internal fun validateDocument(
            dto: JwtVcIssuerMetadataDto,
            iss: String,
        ) {
            // §5.3 — "The `issuer` value returned MUST be identical to the `iss` value of
            // the JWT. If these values are not identical, the data contained in the
            // response MUST NOT be used." Compared exactly: §5 makes `iss` a URL with a
            // defined normal form, so a case-insensitive compare would accept a document
            // the spec does not.
            check(dto.issuer == iss) {
                "jwt-vc-issuer document issuer=${dto.issuer} ≠ iss=$iss; response must not be used"
            }
            // §5.2 — "MUST include either `jwks_uri` or `jwks` ... but not both".
            check(!(dto.jwks != null && dto.jwks_uri != null)) {
                "jwt-vc-issuer document for $iss carries both jwks and jwks_uri; §5.2 says not both"
            }
            check(dto.jwks != null || dto.jwks_uri != null) {
                "jwt-vc-issuer document for $iss carries neither jwks nor jwks_uri; §5.2 requires either"
            }
        }
    }
}
