package dev.digitallabor.elpaso.wallet.presentation

import android.net.Uri
import android.util.Log
import com.nimbusds.jose.EncryptionMethod
import com.nimbusds.jose.JWEAlgorithm
import com.nimbusds.jose.JWEHeader
import com.nimbusds.jose.JWEObject
import com.nimbusds.jose.Payload
import com.nimbusds.jose.crypto.ECDHEncrypter
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.util.Base64URL
import dev.digitallabor.elpaso.wallet.data.crypto.KeyManager
import dev.digitallabor.elpaso.wallet.data.settings.LocaleApplier
import dev.digitallabor.elpaso.wallet.data.settings.SettingsRepository
import dev.digitallabor.elpaso.wallet.data.store.CredentialRepository
import dev.digitallabor.elpaso.wallet.data.store.TransactionRepository
import dev.digitallabor.elpaso.wallet.data.trust.TrustListService
import dev.digitallabor.elpaso.wallet.data.trust.TrustedVerifier
import dev.digitallabor.elpaso.wallet.domain.model.Credential
import dev.digitallabor.elpaso.wallet.domain.model.Format
import dev.digitallabor.elpaso.wallet.presentation.builder.MdocDeviceResponseBuilder
import dev.digitallabor.elpaso.wallet.presentation.builder.SdJwtPresentationBuilder
import dev.digitallabor.elpaso.wallet.presentation.paso.PasoDetector
import dev.digitallabor.elpaso.wallet.presentation.paso.PasoScaClaims
import dev.digitallabor.elpaso.wallet.presentation.paso.RequestIntegrityRecorder
import dev.digitallabor.elpaso.wallet.presentation.paso.WalletInstance
import dev.digitallabor.elpaso.wallet.presentation.txdata.TransactionDataAdapter
import dev.digitallabor.elpaso.wallet.util.B64u
import eu.europa.ec.eudi.openid4vp.Client
import eu.europa.ec.eudi.openid4vp.Consensus
import eu.europa.ec.eudi.openid4vp.DispatchOutcome
import eu.europa.ec.eudi.openid4vp.EncryptionParameters
import eu.europa.ec.eudi.openid4vp.HashAlgorithm
import eu.europa.ec.eudi.openid4vp.JarConfiguration
import eu.europa.ec.eudi.openid4vp.OpenId4VPConfig
import eu.europa.ec.eudi.openid4vp.OpenId4Vp
import eu.europa.ec.eudi.openid4vp.Resolution
import eu.europa.ec.eudi.openid4vp.ResolvedRequestObject
import eu.europa.ec.eudi.openid4vp.ResponseEncryptionConfiguration
import eu.europa.ec.eudi.openid4vp.ResponseMode
import eu.europa.ec.eudi.openid4vp.SupportedClientIdPrefix
import eu.europa.ec.eudi.openid4vp.SupportedTransactionDataType
import eu.europa.ec.eudi.openid4vp.TransactionDataType
import eu.europa.ec.eudi.openid4vp.VPConfiguration
import eu.europa.ec.eudi.openid4vp.VerifiablePresentation
import eu.europa.ec.eudi.openid4vp.VerifiablePresentations
import eu.europa.ec.eudi.openid4vp.VpFormatsSupported
import eu.europa.ec.eudi.openid4vp.X509CertificateTrust
import eu.europa.ec.eudi.openid4vp.dcql.DCQL
import eu.europa.ec.eudi.openid4vp.legalName
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import dev.digitallabor.elpaso.wallet.presentation.txdata.TransactionData as UiTransactionData

/**
 * Drives the OpenID4VP flow using the EUDI `eudi-lib-jvm-openid4vp-kt` library.
 *
 * Three entry paths converge here:
 *   1. Same-device deep link / cross-device QR (`openid4vp://` URIs) → [resolveDeepLink]
 *   2. W3C Digital Credentials API (raw OpenID4VP JSON request) → [resolveDcApi]
 *   3. (future) NFC / BLE — not in scope for the POC
 *
 * The library doesn't yet build mDoc/SD-JWT presentations itself, so we hand the
 * resolved request to the format-specific builders (which embed `transaction_data_hashes`)
 * and pass the resulting strings to `Dispatcher.dispatch(...)` via [Consensus.PositiveConsensus].
 */
class PresentationClient(
    private val repository: CredentialRepository,
    private val trustList: TrustListService,
    private val matcher: DcqlMatcher,
    private val sdJwtBuilder: SdJwtPresentationBuilder,
    private val mdocBuilder: MdocDeviceResponseBuilder,
    private val keyManager: KeyManager,
    private val transactions: TransactionRepository,
    private val httpClient: HttpClient,
    private val settings: SettingsRepository,
    private val credentialMetadataRepository: dev.digitallabor.elpaso.wallet.data.store.CredentialMetadataRepository,
    private val candidateResolver: DcqlCandidateResolver,
) {
    sealed interface State {
        data object Idle : State

        data class Failed(
            val message: String,
            val cause: Throwable? = null,
        ) : State

        data class Resolved(
            /**
             * Populated on the deep-link / cross-device path. Null on the DC API path —
             * the platform delivers a JSON request synchronously and we resolve it locally
             * (no library round-trip), so there's no `ResolvedRequestObject` to dispatch.
             */
            val resolvedRequest: ResolvedRequestObject?,
            val verifier: VerifierIdentity,
            val matches: List<DcqlMatcher.Match>,
            /**
             * Every spec-valid way to satisfy this request (OpenID4VP 1.0 §6.4.2). One
             * carousel page per entry; the visible page is the user's selection. Empty
             * means the request cannot be satisfied and nothing may be disclosed.
             */
            val candidates: List<PresentationCandidate>,
            val transactionData: List<UiTransactionData>,
            val nonce: String,
            val audience: String,
            val responseUri: String?,
            /**
             * The PaSO-targeted `transaction_data` entry (PaSO Core §7.3 simple profile —
             * at most one) when present. Triggers SCA Response Claims in the KB-JWT.
             */
            val pasoEntry: UiTransactionData? = null,
            /**
             * W3C SRI value over the JAR Authorization Request JWT as received, populated
             * iff [pasoEntry] is non-null. Required for the PaSO `request_integrity` claim.
             */
            val requestIntegrity: String? = null,
            /** OID4VP `response_mode` parameter mapped to its spec string. */
            val responseMode: String = "direct_post",
            /**
             * Non-null when the resolution came in through the Android Digital Credentials
             * API. Carries the calling app's origin and the DCQL query id the response must
             * key its `vp_token` on.
             */
            val dcApi: DcApiContext? = null,
            /**
             * Populated when [responseMode] is `dc_api.jwt`. Per OpenID4VP 1.0 §8.3 the
             * response in this mode MUST be a JWE keyed to the verifier's encryption JWK
             * advertised in `client_metadata.jwks`. Null for the plaintext `dc_api` mode.
             */
            val dcApiEncryption: DcApiEncryption? = null,
        ) : State

        data object Dispatching : State

        data class Done(
            val outcome: String,
        ) : State
    }

    data class VerifierIdentity(
        val clientId: String,
        val displayLabel: String,
        val trusted: Boolean,
        val trustedEntry: TrustedVerifier?,
    )

    /**
     * Resolved JWE parameters for a `dc_api.jwt` response. `recipientKey` is the verifier's
     * public encryption JWK (lifted from `client_metadata.jwks`); `alg`/`enc` are the JWE
     * algorithms we'll use when encrypting. We pin `alg` to `ECDH-ES` to match our
     * [OpenId4VPConfig] advertisement; `enc` mirrors the verifier's preference where stated.
     */
    data class DcApiEncryption(
        val recipientKey: ECKey,
        val alg: JWEAlgorithm,
        val enc: EncryptionMethod,
    )

    data class DcApiContext(
        /**
         * Calling app origin in the form `android:apk-key-hash:<sha256>` for native apps,
         * or the web origin (e.g. `https://verifier.example.com`) when forwarded from a
         * privileged browser. Per OpenID4VP 1.0 §B.3.4, the SD-JWT KB-JWT `aud` MUST be
         * this value prefixed with `origin:`.
         */
        val callingAppOrigin: String?,
        /**
         * The protocol identifier the verifier used in its request (e.g.
         * `openid4vp-v1-signed`). Echoed in the response's `protocol` field per the W3C
         * Digital Credentials API so the user agent can route `credential.data` correctly
         * back to the verifier.
         */
        val protocol: String,
        /**
         * The verifier-origin URL (unprefixed). Used directly — without an `origin:` prefix —
         * as the `origin` field of the mdoc `Oid4vpDcApiHandoverInfo`. Distinct from
         * [State.Resolved.audience] (which is `origin:<this>` for SD-JWT KB-JWT use).
         */
        val verifierOrigin: String,
    )

    val state = MutableStateFlow<State>(State.Idle)

    private val trustAllForNow =
        X509CertificateTrust { chain ->
            // We do trust-list resolution downstream in [resolveAndAttachVerifier].
            // Returning true here keeps the library from rejecting before we get a chance
            // to surface the verifier to the user; the consent screen blocks Authorize
            // when [VerifierIdentity.trusted] is false.
            chain.isNotEmpty()
        }

    /**
     * Built fresh per request because [supportedTransactionDataTypes] depends on
     * the union of (a) the wallet's hardcoded baseline types and (b) every
     * `transaction_data_types` key declared in any stored signed credential-metadata
     * JWT (paso-proof-metadata.md §3). Without this the library rejects any PaSO
     * type that wasn't compiled into the wallet — defeating the spec's extension
     * model.
     */
    private suspend fun buildConfig(extraTypes: Set<String> = emptySet()): OpenId4VPConfig {
        val baseline =
            setOf(
                UiTransactionData.PaymentData.TYPE,
                UiTransactionData.QesAuthorization.TYPE,
                UiTransactionData.PasoPayment.TYPE,
                UiTransactionData.EudiScaPayment.TYPE,
            )
        val dynamic = credentialMetadataRepository.knownTransactionDataTypes()
        val allTypes = baseline + dynamic + extraTypes
        return OpenId4VPConfig(
            jarConfiguration = JarConfiguration.Default,
            responseEncryptionConfiguration =
                ResponseEncryptionConfiguration.Supported(
                    supportedAlgorithms = listOf(JWEAlgorithm.ECDH_ES),
                    supportedMethods = listOf(EncryptionMethod.A128GCM, EncryptionMethod.A256GCM),
                ),
            vpConfiguration =
                VPConfiguration(
                    vpFormatsSupported =
                        VpFormatsSupported(
                            sdJwtVc = VpFormatsSupported.SdJwtVc.HAIP,
                            msoMdoc = VpFormatsSupported.MsoMdoc(),
                        ),
                    supportedTransactionDataTypes =
                        allTypes.map { typeId ->
                            SupportedTransactionDataType.SdJwtVc(
                                type = TransactionDataType(typeId),
                                hashAlgorithms = setOf(HashAlgorithm.SHA_256),
                            )
                        },
                ),
            supportedClientIdPrefixes =
                listOf(
                    SupportedClientIdPrefix.X509SanDns(trustAllForNow),
                    SupportedClientIdPrefix.X509Hash(trustAllForNow),
                    SupportedClientIdPrefix.RedirectUri,
                ),
        )
    }

    private suspend fun openId4Vp(extraTypes: Set<String> = emptySet()): OpenId4Vp = OpenId4Vp(buildConfig(extraTypes), httpClient)

    /**
     * Best-effort extraction of `transaction_data` `type` strings from a verifier's
     * OpenID4VP URI BEFORE the library resolves the request. We need this because the
     * library does an exact-match allowlist check on supported types — anything the
     * verifier sends that isn't in our config gets rejected before our consent screen
     * can render. The credential-metadata layer is the real trust gate (verified at
     * consent time); the library's check is only structural.
     *
     * Handles three shapes:
     * - direct `transaction_data=…` query param (rare)
     * - signed JAR via `request=<jwt>` (decoded inline)
     * - signed JAR via `request_uri=<url>` (HTTP-fetched then decoded)
     *
     * Returns an empty set on any failure — the library will then fail with its
     * normal error message, no worse than before.
     */
    private suspend fun extractTransactionDataTypesFromUri(uri: Uri): Set<String> =
        runCatching {
            val raw: String? = uri.getQueryParameter("transaction_data")
            val request: String? = uri.getQueryParameter("request")
            val requestUri: String? = uri.getQueryParameter("request_uri")

            val rawEntries =
                when {
                    !raw.isNullOrBlank() -> decodeTransactionDataParam(raw)
                    !request.isNullOrBlank() -> decodeTransactionDataFromJws(request)
                    !requestUri.isNullOrBlank() -> decodeTransactionDataFromJws(httpClient.get(requestUri).bodyAsText())
                    else -> emptyList()
                }

            rawEntries.mapNotNullTo(mutableSetOf()) { rawEntry ->
                runCatching {
                    val decoded =
                        android.util.Base64.decode(
                            rawEntry,
                            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
                        )
                    val obj =
                        kotlinx.serialization.json.Json
                            .parseToJsonElement(decoded.decodeToString())
                            .jsonObject
                    obj["type"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
            }
        }.onFailure {
            Log.w(LOG_TAG, "extractTransactionDataTypesFromUri failed (ignored)", it)
        }.getOrDefault(emptySet())

    private fun decodeTransactionDataParam(value: String): List<String> =
        runCatching {
            // The query param can be either a JSON array of base64url strings or a single
            // base64url string — try array first, fall back to single value.
            val parsed =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(value)
            when (parsed) {
                is kotlinx.serialization.json.JsonArray -> parsed.mapNotNull { it.jsonPrimitive.contentOrNull }
                else -> listOf(value)
            }
        }.getOrDefault(listOf(value))

    private fun decodeTransactionDataFromJws(jwt: String): List<String> =
        runCatching {
            val payloadJson =
                com.nimbusds.jwt.SignedJWT
                    .parse(jwt)
                    .payload
                    .toString()
            val root =
                kotlinx.serialization.json.Json
                    .parseToJsonElement(payloadJson)
                    .jsonObject
            val arr = root["transaction_data"] as? kotlinx.serialization.json.JsonArray ?: return@runCatching emptyList()
            arr.mapNotNull { it.jsonPrimitive.contentOrNull }
        }.getOrDefault(emptyList())

    suspend fun resolveDeepLink(uri: Uri): Result<State.Resolved> =
        runCatching {
            // Wipe any prior terminal state (Done/Failed) from a previous presentation so
            // the UI doesn't see a stale outcome and auto-navigate before this resolution
            // has a chance to run. Without this, the second QR scan in a single app session
            // bounces back to Home immediately.
            state.value = State.Idle
            Log.i(
                LOG_TAG,
                "resolveDeepLink client_id=${uri.getQueryParameter("client_id")} " +
                    "request_uri=${uri.getQueryParameter("request_uri")} " +
                    "has_request=${uri.getQueryParameter("request") != null}",
            )
            val extraTypes = extractTransactionDataTypesFromUri(uri)
            if (extraTypes.isNotEmpty()) {
                Log.i(LOG_TAG, "resolveDeepLink registering ad-hoc transaction_data types: $extraTypes")
            }
            when (val res = openId4Vp(extraTypes).resolveRequestUri(uri.toString())) {
                is Resolution.Success -> attachVerifier(res.requestObject, uri)
                is Resolution.Invalid -> error("Invalid presentation request: ${res.error}")
            }
        }.onFailure {
            Log.w(LOG_TAG, "resolveDeepLink failed", it)
            state.value = State.Failed(it.message ?: "Failed to resolve request", it)
        }

    /**
     * Digital Credentials API request path.
     *
     * The platform delivers an OpenID4VP request as inline JSON via the registry intent
     * (the wallet was already chosen by the WASM matcher). We parse the fields we need
     * locally — there's no HTTP round-trip and no `request_uri` to fetch — and prebuild
     * a [State.Resolved] narrowed to the user-selected credential.
     *
     * [callingAppOrigin] is the calling app's identity expressed per OpenID4VP DC API
     * profile (`android:apk-key-hash:…` for native callers, `web-origin:https://…` when
     * forwarded from a privileged browser). [selectedCredentialId] is the entry id the
     * user picked in the system selector; the registry already restricted matches to
     * disclosable claims for that one credential.
     */
    suspend fun resolveDcApi(
        rawRequestJson: String,
        callingAppOrigin: String?,
        selectedCredentialId: String?,
    ): Result<State.Resolved> =
        runCatching {
            state.value = State.Idle
            Log.i(
                LOG_TAG,
                "resolveDcApi origin=$callingAppOrigin selected=$selectedCredentialId " +
                    "payload_len=${rawRequestJson.length}",
            )
            val envelope = parseDcApiEnvelope(Json.parseToJsonElement(rawRequestJson).jsonObject)
            Log.i(
                LOG_TAG,
                "resolveDcApi protocol=${envelope.protocol} signed=${envelope.signedJwt != null} " +
                    "x5c_chain_len=${envelope.x5cChain.size}",
            )
            val request = envelope.openId4VpRequest
            val clientId =
                request["client_id"]?.jsonPrimitive?.contentOrNull
                    ?: callingAppOrigin
                    ?: "unknown-verifier"
            val nonce =
                request["nonce"]?.jsonPrimitive?.contentOrNull
                    ?: run {
                        Log.w(
                            LOG_TAG,
                            "DC API request body keys=${request.keys} (no nonce) " +
                                "raw_envelope_keys=${Json.parseToJsonElement(rawRequestJson).jsonObject.keys} " +
                                "raw_head=${rawRequestJson.take(400)}",
                        )
                        error("DC API request missing nonce")
                    }
            val dcqlJson =
                request["dcql_query"]?.jsonObject
                    ?: error("DC API request missing dcql_query")
            val dcql = dcApiJson.decodeFromString(DCQL.serializer(), dcqlJson.toString())

            val allCredentials = repository.observeAll().first()
            val eligibleCredentials =
                if (selectedCredentialId != null) {
                    allCredentials.filter { it.id == selectedCredentialId }
                } else {
                    allCredentials
                }
            val matches = matcher.match(dcql, eligibleCredentials)
            val presentationCandidates = candidateResolver.resolve(dcql, matches)
            Log.i(
                LOG_TAG,
                "resolveDcApi eligible=${eligibleCredentials.size} matches=${matches.size} " +
                    "candidates=${presentationCandidates.size} " +
                    "match_ids=${matches.map { it.credentialId }} client_id=$clientId nonce_len=${nonce.length}",
            )
            if (matches.isEmpty()) error("No stored credential satisfies the DC API request")
            // §6.4.2: if a required credential set cannot be satisfied, disclose nothing.
            // Fail closed rather than send a partial response.
            if (presentationCandidates.isEmpty()) {
                error("No stored credential satisfies the DC API request's credential_sets")
            }

            val txData = parseTransactionData(request)
            PasoDetector.rejectAdvancedProfile(txData)
            val pasoEntry = PasoDetector.pasoEntry(txData)
            // PaSO Core §6.1 binds the SCA response to the verbatim request via `request_integrity`.
            // For DC API we can supply that iff the verifier used `openid4vp-v1-signed` — the
            // compact JWS is what we hash. Unsigned DC API requests can't satisfy PaSO.
            val requestIntegrity =
                if (pasoEntry != null) {
                    envelope.signedJwt?.let(RequestIntegrityRecorder::hashJwt)
                        ?: throw PasoDetector.PasoUnsupportedException(
                            reasonStringRes = dev.digitallabor.elpaso.wallet.R.string.paso_request_must_be_signed,
                            message = "PaSO over the Digital Credentials API requires protocol=openid4vp-v1-signed",
                        )
                } else {
                    null
                }

            val devMode = settings.developerMode.first()
            // Developer mode is an explicit opt-out of trust enforcement — used while testing
            // against verifiers whose certs / client_ids aren't on our list yet. Don't even
            // consult the trust list so it can't surface a stale or partial match in the UI.
            val trusted =
                if (devMode) {
                    null
                } else {
                    trustList.resolveVerifier(
                        clientId,
                        envelope.x5cChain.takeIf { it.isNotEmpty() },
                    )
                }
            val displayLabel = trusted?.label ?: callingAppOrigin ?: clientId
            val verifier =
                VerifierIdentity(
                    clientId = clientId,
                    displayLabel = displayLabel,
                    trusted = devMode || trusted != null,
                    trustedEntry = trusted,
                )

            val responseMode = request["response_mode"]?.jsonPrimitive?.contentOrNull ?: "dc_api"
            // OpenID4VP 1.0 §8.3: `dc_api.jwt` mandates an encrypted JWE response. Fail fast
            // here if the verifier asks for it but didn't supply a usable encryption key.
            val dcApiEncryption = parseDcApiEncryption(request, responseMode)

            // OpenID4VP 2.0 §B.3.4 (DC API): KB-JWT `aud` is the verifier's Origin prefixed
            // with `origin:`. The Origin is implied by the client_id prefix — for an
            // `x509_san_dns:<host>` verifier identity, the Origin is `https://<host>`. We rely
            // on the verifier's own client_id rather than `CallingAppInfo.getOrigin()` because
            // the latter only works when the calling browser is in our privileged-apps list,
            // whereas the client_id is always present and self-describing in a signed request.
            val verifierOrigin = deriveVerifierOrigin(clientId, callingAppOrigin)
            val audience = "origin:$verifierOrigin"
            Log.i(
                LOG_TAG,
                "resolveDcApi response_mode=$responseMode " +
                    "encryption=${if (dcApiEncryption != null) "${dcApiEncryption.alg}/${dcApiEncryption.enc}" else "none"} " +
                    "audience=$audience verifier_origin=$verifierOrigin",
            )

            State
                .Resolved(
                    resolvedRequest = null,
                    verifier = verifier,
                    matches = matches,
                    candidates = presentationCandidates,
                    transactionData = txData,
                    nonce = nonce,
                    audience = audience,
                    responseUri = null,
                    pasoEntry = pasoEntry,
                    requestIntegrity = requestIntegrity,
                    responseMode = responseMode,
                    dcApi =
                        DcApiContext(
                            callingAppOrigin = callingAppOrigin,
                            // Echo the request's protocol verbatim. Default to `openid4vp` if the
                            // envelope omitted it — older callers don't always include the field.
                            protocol = envelope.protocol ?: "openid4vp",
                            verifierOrigin = verifierOrigin,
                        ),
                    dcApiEncryption = dcApiEncryption,
                ).also { state.value = it }
        }.onFailure {
            Log.w(LOG_TAG, "resolveDcApi failed", it)
            state.value = State.Failed(it.message ?: "DC API request handling failed", it)
        }

    /**
     * Parsed shape of a W3C Digital Credentials request envelope. The platform either
     * wraps the request as `{"providers":[{"protocol":"…","request":…}]}` (early drafts),
     * `{"requests":[{"protocol":"…","data":…}]}` (current W3C draft Chrome uses), or hands
     * us the OpenID4VP request directly. For `openid4vp-v1-signed` the inner payload is a
     * compact JWS — decode its payload, capture bytes for hashing, lift `x5c` for trust.
     */
    private data class DcApiEnvelope(
        val protocol: String?,
        val openId4VpRequest: JsonObject,
        val signedJwt: String?,
        val x5cChain: List<X509Certificate>,
    )

    private fun parseDcApiEnvelope(root: JsonObject): DcApiEnvelope {
        // Envelope shapes are nested unpredictably across drafts/browsers:
        //   { requests:  [{ protocol, data: { request: "<jws>" }}] }   ← Chrome / W3C draft
        //   { requests:  [{ protocol, data:  <openid4vp-object>   }] }
        //   { providers: [{ protocol, request: "<jws>"            }] }
        //   { providers: [{ protocol, request: <openid4vp-object> }] }
        //   { protocol, request|data: ... }                             ← single shorthand
        //   <openid4vp-object>                                          ← envelope-less
        // The inner payload may also be a JSON-encoded string (re-parse). Rather than
        // trying to enumerate every combination, walk through wrapper objects until we
        // hit the real OpenID4VP body (identified by `nonce` / `dcql_query`) or a JWS.
        val entries = (root["requests"] ?: root["providers"])?.jsonArray
        val entry = entries?.firstOrNull() as? JsonObject

        val protocol =
            entry?.get("protocol")?.jsonPrimitive?.contentOrNull
                ?: root["protocol"]?.jsonPrimitive?.contentOrNull

        Log.i(
            LOG_TAG,
            "parseDcApiEnvelope envelope=${classifyEnvelope(root)} protocol=$protocol " +
                "entry_keys=${entry?.keys}",
        )

        // Start from the envelope entry if present, otherwise the root.
        var current: JsonElement = entry ?: root
        repeat(MAX_DC_API_UNWRAP_DEPTH) {
            when (current) {
                is JsonPrimitive -> {
                    val c = current as JsonPrimitive
                    if (!c.isString) error("DC API inner payload is a non-string primitive")
                    val s = c.content
                    val looksLikeJws = s.count { it == '.' } == 2 && !s.trimStart().startsWith("{")
                    if (protocol == PROTOCOL_OPENID4VP_SIGNED || looksLikeJws) {
                        val (payload, x5c) = decodeCompactJws(s)
                        return DcApiEnvelope(protocol, payload, s, x5c)
                    }
                    // Stringified JSON: re-parse and continue unwrapping.
                    current = Json.parseToJsonElement(s)
                }

                is JsonObject -> {
                    val o = current as JsonObject
                    // Reached the OpenID4VP request itself.
                    if (o.containsKey("nonce") || o.containsKey("dcql_query")) {
                        return DcApiEnvelope(protocol, o, signedJwt = null, x5cChain = emptyList())
                    }
                    // Drill one wrapper deeper. `data` first (current W3C draft),
                    // `request` second (older drafts and JWS-bearing envelopes).
                    val next = o["data"] ?: o["request"]
                    if (next == null) {
                        // No further wrapper and not a recognisable body. Surface the
                        // current keys so the caller's nonce-missing error is informative.
                        return DcApiEnvelope(protocol, o, signedJwt = null, x5cChain = emptyList())
                    }
                    current = next
                }

                else -> {
                    error("Unrecognised DC API element kind: ${current::class.simpleName}")
                }
            }
        }
        error("DC API envelope nested deeper than $MAX_DC_API_UNWRAP_DEPTH levels — refusing to unwrap further")
    }

    private fun classifyEnvelope(root: JsonObject): String =
        when {
            root["requests"] != null -> "requests[]"
            root["providers"] != null -> "providers[]"
            root["protocol"] != null -> "single"
            root["nonce"] != null || root["dcql_query"] != null -> "bare"
            else -> "unknown"
        }

    /**
     * Derive the verifier's Origin from the OpenID4VP `client_id` per OpenID4VP 2.0
     * §B.3.4: the KB-JWT `aud` must be `origin:<verifier-origin>`. For the supported
     * client_id prefixes the origin is implicit:
     *
     * - `x509_san_dns:<host>` → `https://<host>` (host MUST also appear as a dnsName SAN
     *   in the leaf cert of the signed request's x5c chain; today the WASM matcher / our
     *   trust list resolution covers that)
     * - `web-origin:<origin>` → the origin verbatim
     * - `redirect_uri:<uri>` → the scheme+authority of the URI
     *
     * If nothing matches we fall back to whatever the platform handed us via
     * `CallingAppInfo` — better than nothing for non-standard verifier IDs, but a verifier
     * checking aud against its expected Origin will likely reject. Logged so it's visible.
     */
    private fun deriveVerifierOrigin(
        clientId: String,
        callingAppOrigin: String?,
    ): String {
        val prefixed =
            clientId.indexOf(':').takeIf { it > 0 }?.let { idx ->
                clientId.substring(0, idx) to clientId.substring(idx + 1)
            }
        return when (prefixed?.first) {
            "x509_san_dns" -> {
                "https://${prefixed.second}"
            }

            "web-origin" -> {
                prefixed.second
            }

            "redirect_uri" -> {
                Uri.parse(prefixed.second).let { uri ->
                    val scheme = uri.scheme ?: "https"
                    val authority = uri.authority ?: prefixed.second
                    "$scheme://$authority"
                }
            }

            else -> {
                Log.w(
                    LOG_TAG,
                    "deriveVerifierOrigin: no recognised client_id prefix in '$clientId' — " +
                        "falling back to callingAppOrigin=$callingAppOrigin",
                )
                callingAppOrigin ?: clientId
            }
        }
    }

    private fun decodeCompactJws(jwt: String): Pair<JsonObject, List<X509Certificate>> {
        val parts = jwt.split('.')
        require(parts.size == 3) { "Malformed signed DC API request: expected 3 JWS segments" }
        val header = Json.parseToJsonElement(B64u.decode(parts[0]).decodeToString()).jsonObject
        val payload = Json.parseToJsonElement(B64u.decode(parts[1]).decodeToString()).jsonObject
        val x5c =
            header["x5c"]
                ?.jsonArray
                ?.mapNotNull { entry ->
                    val b64 = (entry as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    runCatching {
                        val der = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
                        CertificateFactory
                            .getInstance("X.509")
                            .generateCertificate(der.inputStream()) as X509Certificate
                    }.getOrNull()
                }.orEmpty()
        return payload to x5c
    }

    /**
     * Extract the verifier's response-encryption parameters from the OpenID4VP request when
     * `response_mode=dc_api.jwt`. Returns null for the plaintext `dc_api` mode; throws when
     * encryption is required but the verifier didn't supply a usable EC encryption key in
     * `client_metadata.jwks`.
     *
     * Algorithm selection: we hard-pin `alg` to `ECDH-ES` (the only key-wrap we advertise
     * in [OpenId4VPConfig]); `enc` honours the first entry of
     * `encrypted_response_enc_values_supported` if present, else defaults to A128GCM.
     */
    private fun parseDcApiEncryption(
        request: JsonObject,
        responseMode: String,
    ): DcApiEncryption? {
        if (responseMode != "dc_api.jwt") return null
        val clientMetadata =
            request["client_metadata"]?.jsonObject
                ?: error("response_mode=dc_api.jwt but request is missing client_metadata")
        val jwksJson =
            clientMetadata["jwks"]?.jsonObject
                ?: error("response_mode=dc_api.jwt but client_metadata.jwks is missing")
        val keys =
            jwksJson["keys"]?.jsonArray
                ?: error("client_metadata.jwks has no 'keys' array")

        val encKey =
            keys.firstNotNullOfOrNull { element ->
                runCatching {
                    val jwk = JWK.parse(element.toString())
                    val ec = jwk as? ECKey ?: return@runCatching null
                    // Accept keys flagged `use=enc` or with no `use` restriction. Verifier
                    // conformance here is patchy (Keycloak omits the field), so we don't
                    // reject unmarked keys outright.
                    val use = ec.keyUse
                    if (use != null && use != KeyUse.ENCRYPTION) return@runCatching null
                    ec
                }.getOrNull()
            } ?: error("client_metadata.jwks has no usable EC encryption key")

        val enc =
            clientMetadata["encrypted_response_enc_values_supported"]
                ?.jsonArray
                ?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.let(EncryptionMethod::parse)
                ?: EncryptionMethod.A128GCM

        return DcApiEncryption(
            recipientKey = encKey,
            alg = JWEAlgorithm.ECDH_ES,
            enc = enc,
        )
    }

    private fun parseTransactionData(request: JsonObject): List<UiTransactionData> {
        val entries = request["transaction_data"]?.jsonArray ?: return emptyList()
        return entries.mapNotNull { element ->
            val raw =
                (element as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                    ?: return@mapNotNull null
            runCatching { UiTransactionData.parse(raw) }.getOrNull()
        }
    }

    private val dcApiJson = Json { ignoreUnknownKeys = true }

    private suspend fun attachVerifier(
        req: ResolvedRequestObject,
        originalUri: Uri,
    ): State.Resolved {
        val (clientId, x5c) = clientIdAndChain(req.client)
        val audience = kbJwtAudience(req.client)
        // Trust-list entries are keyed on the unprefixed OriginalClientId, so resolution
        // and display keep using [clientId] rather than [audience].
        val trusted = trustList.resolveVerifier(clientId, x5c)
        val devMode = settings.developerMode.first()
        val displayLabel = req.client.legalName() ?: trusted?.label ?: clientId
        val verifier =
            VerifierIdentity(
                clientId = clientId,
                displayLabel = displayLabel,
                trusted = devMode || trusted != null,
                trustedEntry = trusted,
            )

        val allCredentials = repository.observeAll().first()
        val matches = matcher.match(req.query, allCredentials)
        val presentationCandidates = candidateResolver.resolve(req.query, matches)
        val txData =
            (req.transactionData ?: emptyList())
                .map(TransactionDataAdapter::fromLibrary)

        // PaSO Core §7.3: simple profile only.
        PasoDetector.rejectAdvancedProfile(txData)
        val pasoEntry = PasoDetector.pasoEntry(txData)
        val requestIntegrity =
            if (pasoEntry != null) {
                // PaSO Core §3 + §6.1 — PaSO presentations require a signed JAR request, and we
                // must report request_integrity over the JWT as received. If neither was used,
                // refuse to proceed.
                captureRequestIntegrity(originalUri)
                    ?: throw PasoDetector.PasoUnsupportedException(
                        reasonStringRes = dev.digitallabor.elpaso.wallet.R.string.paso_request_must_be_signed,
                        message = "PaSO request was not delivered as JAR (no request/request_uri)",
                    )
            } else {
                null
            }

        return State
            .Resolved(
                resolvedRequest = req,
                verifier = verifier,
                matches = matches,
                candidates = presentationCandidates,
                transactionData = txData,
                nonce = req.nonce,
                audience = audience,
                responseUri = req.responseMode.responseUri(),
                pasoEntry = pasoEntry,
                requestIntegrity = requestIntegrity,
                responseMode = req.responseMode.toSpecString(),
            ).also { state.value = it }
    }

    private fun captureRequestIntegrity(originalUri: Uri): String? {
        val inline = originalUri.getQueryParameter("request")
        if (inline != null) return RequestIntegrityRecorder.hashJwt(inline)
        val requestUri = originalUri.getQueryParameter("request_uri") ?: return null
        return RequestIntegrityRecorder.consume(requestUri)
    }

    /**
     * `Log.i` truncates around ~4076 chars per line; split long payloads into chunks so
     * the full string survives logcat. Debug-only — feel free to gate behind a flag.
     */
    private fun logChunked(
        tag: String,
        prefix: String,
        payload: String,
    ) {
        val max = 3500
        if (payload.length <= max) {
            Log.i(tag, "$prefix=$payload")
            return
        }
        val total = (payload.length + max - 1) / max
        var idx = 0
        var part = 0
        while (idx < payload.length) {
            val end = (idx + max).coerceAtMost(payload.length)
            Log.i(tag, "$prefix[$part/$total]=${payload.substring(idx, end)}")
            idx = end
            part++
        }
    }

    private fun ResponseMode.toSpecString(): String =
        when (this) {
            is ResponseMode.DirectPost -> "direct_post"
            is ResponseMode.DirectPostJwt -> "direct_post.jwt"
            is ResponseMode.Query -> "query"
            is ResponseMode.QueryJwt -> "query.jwt"
            is ResponseMode.Fragment -> "fragment"
            is ResponseMode.FragmentJwt -> "fragment.jwt"
        }

    private fun ResponseMode.responseUri(): String? =
        when (this) {
            is ResponseMode.DirectPost -> this.responseURI.toString()
            is ResponseMode.DirectPostJwt -> this.responseURI.toString()
            else -> null
        }

    /**
     * The value the KB-JWT `aud` (and the mdoc handover `clientId`) must carry on the
     * non-DC-API paths.
     *
     * OpenID4VP 1.0 — SD-JWT VC Presentation Response: "the `aud` claim MUST be the value
     * of the Client Identifier, except for requests over the DC API" (identical wording
     * for mdoc). The Client Identifier **includes its prefix** — the spec's own example is
     * `x509_hash:Uvo3HtuIxuhC92rShpgqcT3YXwrqRxWEviRiA0OZszk`, and it removes any ambiguity
     * with: "The presentation would contain the full `verifier_attestation:example-client`
     * string as the audience … and the same full string would be used as the Client
     * Identifier anywhere in the OAuth flow."
     *
     * The library's [Client.clientId] is the *unprefixed* `OriginalClientId`, so using it
     * directly produced `aud` values verifiers rejected with "KB-JWT audience mismatch:
     * presented \"<thumbprint>\", expected one of [\"x509_hash:<thumbprint>\"]".
     * [Client.id] reconstitutes the prefixed form; `pre-registered` client ids legitimately
     * have no prefix and pass through verbatim.
     *
     * Not used for the DC API path — there the spec's exception applies and the audience is
     * `origin:<verifier-origin>` (see [deriveVerifierOrigin]).
     */
    private fun kbJwtAudience(client: Client): String = client.id.clientId

    private fun clientIdAndChain(client: Client): Pair<String, List<X509Certificate>?> =
        when (client) {
            is Client.Preregistered -> client.clientId to null
            is Client.RedirectUri -> client.clientId.toString() to null
            is Client.DecentralizedIdentifier -> client.clientId.toString() to null
            is Client.VerifierAttestation -> client.clientId to null
            is Client.X509SanDns -> client.clientId to listOf(client.cert)
            is Client.X509Hash -> client.clientId to listOf(client.cert)
        }

    /**
     * Build verifiable presentations for [matchesWithSignatures], group them under their
     * DCQL `queryId`s, dispatch via the library, and log each disclosure.
     *
     * Each [Signature] must come from [dev.digitallabor.elpaso.wallet.session.BiometricAuthorizer] after
     * the user has authenticated for the corresponding credential's device key. Because
     * device keys are configured with per-use auth, callers must obtain one Signature per
     * match (the UI prompts biometrics sequentially).
     *
     * DCQL `credential_sets` can require multiple credentials in one response (e.g. a PaSO
     * SCA credential plus an age credential). Sending only one presentation leaves the
     * verifier reporting "Credential sets not satisfied".
     */
    suspend fun authorize(
        resolved: State.Resolved,
        matchesWithSignatures: List<Pair<DcqlMatcher.Match, Signature>>,
    ): Result<DispatchOutcome> =
        runCatching {
            require(matchesWithSignatures.isNotEmpty()) { "authorize() requires at least one match" }
            val resolvedRequest =
                resolved.resolvedRequest
                    ?: error("authorize() requires a deep-link resolution; use authorizeDcApi() for DC API")
            state.value = State.Dispatching

            // The mdoc `mdocGeneratedNonce` in SessionTranscript and the JWE `apu` MUST be the
            // same base64url string, otherwise the verifier can't reconstruct the transcript
            // when validating the device signature. Generate once, use for every mdoc
            // presentation in this response and for the JWE apu.
            val mdocGeneratedNonce = randomBase64UrlNonce(16)

            // Build one presentation per match, keyed by DCQL queryId.
            val presentationsByQuery = mutableMapOf<eu.europa.ec.eudi.openid4vp.dcql.QueryId, MutableList<VerifiablePresentation>>()
            val usedCredentials = mutableListOf<Credential>()
            for ((match, authorizedSignature) in matchesWithSignatures) {
                val credential =
                    repository.byId(match.credentialId)
                        ?: error("Credential ${match.credentialId} not found")

                // PaSO Core §6.1 SCA Response Claims — only when the request involves a PaSO
                // transaction_data entry AND we're presenting an SD-JWT VC (§6.3 profile).
                val pasoEntry = resolved.pasoEntry?.takeIf { match.format == Format.SdJwtVc }
                val pasoClaims = if (pasoEntry != null) buildPasoClaims(resolved, pasoEntry) else null

                val presentationString =
                    buildPresentation(
                        resolved,
                        match,
                        credential,
                        authorizedSignature,
                        mdocGeneratedNonce,
                        pasoClaims,
                    )
                presentationsByQuery
                    .getOrPut(matcher.queryIdFor(match)) { mutableListOf() }
                    .add(VerifiablePresentation.Generic(presentationString))
                usedCredentials += credential
            }

            val verifiablePresentations = VerifiablePresentations(presentationsByQuery)

            val encryptionParameters: EncryptionParameters? =
                resolvedRequest.responseEncryptionSpecification?.let {
                    EncryptionParameters.DiffieHellman(apu = Base64URL(mdocGeneratedNonce))
                }

            val outcome =
                openId4Vp().dispatch(
                    request = resolvedRequest,
                    consensus = Consensus.PositiveConsensus(verifiablePresentations),
                    encryptionParameters = encryptionParameters,
                )

            val outcomeLabel =
                when (outcome) {
                    is DispatchOutcome.RedirectURI -> "redirect"
                    is DispatchOutcome.VerifierResponse.Accepted -> "accepted"
                    DispatchOutcome.VerifierResponse.Rejected -> "rejected"
                }
            for ((match, _) in matchesWithSignatures) {
                val credential = usedCredentials.first { it.id == match.credentialId }
                transactions.log(
                    verifierId = resolved.verifier.clientId,
                    verifierLabel = resolved.verifier.displayLabel,
                    credentialId = credential.id,
                    fieldsDisclosed = match.requestedClaimPaths.map { it.joinToString(".") },
                    transactionSummary = resolved.transactionData.firstOrNull()?.let(::summarise),
                    outcome = outcomeLabel,
                )
                repository.markUsed(credential)
            }
            state.value = State.Done("ok")
            outcome
        }.onFailure {
            Log.w(LOG_TAG, "authorize failed", it)
            state.value = State.Failed(it.message ?: "Presentation failed", it)
        }

    /** Convenience overload for the legacy single-credential flow. */
    suspend fun authorize(
        resolved: State.Resolved,
        match: DcqlMatcher.Match,
        authorizedSignature: Signature,
    ): Result<DispatchOutcome> = authorize(resolved, listOf(match to authorizedSignature))

    /**
     * DC API authorisation: builds the presentation, packages it into an OpenID4VP DC API
     * response object, logs the transaction, and returns the response JSON for the host
     * activity to hand back to the platform via
     * [androidx.credentials.provider.PendingIntentHandler.setGetCredentialResponse].
     *
     * No HTTP dispatch happens here — the platform shuttles the response to the verifier.
     */
    suspend fun authorizeDcApi(
        resolved: State.Resolved,
        matchesWithSignatures: List<Pair<DcqlMatcher.Match, Signature>>,
    ): Result<String> =
        runCatching {
            require(matchesWithSignatures.isNotEmpty()) { "authorizeDcApi() requires at least one match" }
            require(resolved.dcApi != null) { "authorizeDcApi() requires a DC API resolution" }
            state.value = State.Dispatching

            // Both SD-JWT VC and mdoc are valid DC API credential formats — the wallet's
            // matcher is the gatekeeper on what stored credentials get surfaced. mdoc
            // presentations use the `Oid4vpDcApiHandover` SessionTranscript variant; see
            // `buildPresentation`'s `Format.MsoMdoc` branch.

            // Shared mdocGeneratedNonce used as JWE `apu` for `dc_api.jwt` responses. The
            // current OID4VP DC API handover does NOT include this in the SessionTranscript
            // (only the draft-18 deep-link form does), but we still need it for ECDH
            // PartyUInfo so the verifier can derive the same content-encryption key.
            val mdocGeneratedNonce = randomBase64UrlNonce(16)

            val presentationsByQuery = mutableMapOf<String, MutableList<String>>()
            val usedCredentials = mutableListOf<Credential>()
            var anyPaso = false
            for ((match, authorizedSignature) in matchesWithSignatures) {
                val credential =
                    repository.byId(match.credentialId)
                        ?: error("Credential ${match.credentialId} not found")

                // PaSO Core §6.1 SCA Response Claims. Triggered when the verifier sent a PaSO
                // transaction_data entry AND we're presenting an SD-JWT VC (§6.3 profile).
                // For multi-credential DC API requests this currently splices the same SCA
                // Response Claims into every SD-JWT VC presentation — the transaction_data
                // entry doesn't yet carry a `credential_ids` targeting field on our side.
                val pasoEntry = resolved.pasoEntry?.takeIf { match.format == Format.SdJwtVc }
                val pasoClaims = if (pasoEntry != null) buildPasoClaims(resolved, pasoEntry) else null
                if (pasoClaims != null) anyPaso = true

                val presentationString =
                    buildPresentation(
                        resolved = resolved,
                        match = match,
                        credential = credential,
                        authorizedSignature = authorizedSignature,
                        mdocGeneratedNonce = mdocGeneratedNonce,
                        pasoClaims = pasoClaims,
                    )
                presentationsByQuery.getOrPut(match.queryId) { mutableListOf() }.add(presentationString)
                usedCredentials += credential
            }

            val innerData = buildDcApiInnerData(presentationsByQuery)
            // `dc_api.jwt` → encrypt the inner OpenID4VP response as a JWE keyed to the
            // verifier's encryption JWK (resolved upfront in resolveDcApi). `dc_api` ships
            // the inner object directly as the envelope's `data`.
            val responseData =
                resolved.dcApiEncryption?.let { spec ->
                    val jwe =
                        encryptDcApiResponse(
                            innerJson = innerData.toString(),
                            spec = spec,
                            requestNonce = resolved.nonce,
                        )
                    kotlinx.serialization.json.buildJsonObject {
                        put("response", kotlinx.serialization.json.JsonPrimitive(jwe))
                    }
                } ?: innerData
            val responseJson = buildDcApiResponseEnvelope(responseData, resolved.dcApi.protocol)
            Log.i(
                LOG_TAG,
                "authorizeDcApi success credentials=${matchesWithSignatures.size} " +
                    "queryIds=${presentationsByQuery.keys} " +
                    "protocol=${resolved.dcApi.protocol} " +
                    "encrypted=${resolved.dcApiEncryption != null} " +
                    "response_len=${responseJson.length} paso=$anyPaso",
            )
            // Full content dump so the exact bytes we hand to the platform are inspectable
            // for diagnosis. logcat truncates at ~4k per line, so chunk.
            logChunked(LOG_TAG, "authorizeDcApi responseJson", responseJson)

            for ((match, _) in matchesWithSignatures) {
                val credential = usedCredentials.first { it.id == match.credentialId }
                transactions.log(
                    verifierId = resolved.verifier.clientId,
                    verifierLabel = resolved.verifier.displayLabel,
                    credentialId = credential.id,
                    fieldsDisclosed = match.requestedClaimPaths.map { it.joinToString(".") },
                    transactionSummary = resolved.transactionData.firstOrNull()?.let(::summarise),
                    outcome = "dc_api",
                )
                repository.markUsed(credential)
            }
            state.value = State.Done("dc_api")
            responseJson
        }.onFailure {
            Log.w(LOG_TAG, "authorizeDcApi failed", it)
            state.value = State.Failed(it.message ?: "DC API presentation failed", it)
        }

    /** Convenience overload for the legacy single-credential DC API flow. */
    suspend fun authorizeDcApi(
        resolved: State.Resolved,
        match: DcqlMatcher.Match,
        authorizedSignature: Signature,
    ): Result<String> = authorizeDcApi(resolved, listOf(match to authorizedSignature))

    /**
     * Build the inner OpenID4VP response object — `{"vp_token": {"<query_id>": ["<vp>", …]}}`.
     * `vp_token` maps each DCQL query id to an **array** of presentation strings (one per
     * matched credential disclosed for that query). For multi-credential DCQL requests
     * (e.g. `credential_sets` combining an SCA credential and an age credential) we emit
     * one entry per queryId. We always wrap the value in an array — even for a single
     * presentation — because verifiers that strictly validate the schema reject scalar
     * string values.
     *
     * For `response_mode=dc_api` this object is the W3C DC API envelope's `data` directly;
     * for `dc_api.jwt` it becomes the JWE plaintext and the envelope's `data` is instead
     * `{"response": "<jwe>"}` (see [encryptDcApiResponse]).
     */
    private fun buildDcApiInnerData(presentationsByQuery: Map<String, List<String>>): JsonObject {
        val vpToken =
            kotlinx.serialization.json.buildJsonObject {
                for ((queryId, presentations) in presentationsByQuery) {
                    put(
                        queryId,
                        kotlinx.serialization.json.JsonArray(
                            presentations.map { kotlinx.serialization.json.JsonPrimitive(it) },
                        ),
                    )
                }
            }
        return kotlinx.serialization.json.buildJsonObject {
            put("vp_token", vpToken)
        }
    }

    /**
     * Wrap [data] in the W3C Digital Credentials API response envelope:
     * `{ "protocol": "<echo>", "data": { … } }`. The user agent forwards `data` to the
     * verifier as `credential.data`.
     */
    private fun buildDcApiResponseEnvelope(
        data: JsonObject,
        protocol: String,
    ): String {
        val root =
            kotlinx.serialization.json.buildJsonObject {
                put("protocol", kotlinx.serialization.json.JsonPrimitive(protocol))
                put("data", data)
            }
        return root.toString()
    }

    /**
     * Encrypt the OpenID4VP response object as a JWE per OpenID4VP 1.0 §8.3 (the
     * `dc_api.jwt` response mode). ECDH-ES requires `apu` (fresh wallet PartyUInfo) and
     * `apv` (= Base64URL of the authorization request `nonce` bytes) in the JWE header so
     * the verifier can rebind the response to its original request.
     */
    private fun encryptDcApiResponse(
        innerJson: String,
        spec: DcApiEncryption,
        requestNonce: String,
    ): String {
        val apuBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val header =
            JWEHeader
                .Builder(spec.alg, spec.enc)
                .agreementPartyUInfo(Base64URL.encode(apuBytes))
                .agreementPartyVInfo(Base64URL.encode(requestNonce))
                .apply { spec.recipientKey.keyID?.let { keyID(it) } }
                .build()
        val jwe = JWEObject(header, Payload(innerJson))
        jwe.encrypt(ECDHEncrypter(spec.recipientKey))
        return jwe.serialize()
    }

    private fun buildPresentation(
        resolved: State.Resolved,
        match: DcqlMatcher.Match,
        credential: Credential,
        authorizedSignature: Signature,
        mdocGeneratedNonce: String,
        pasoClaims: PasoScaClaims?,
    ): String =
        when (match.format) {
            Format.SdJwtVc -> {
                sdJwtBuilder.build(
                    SdJwtPresentationBuilder.Input(
                        rawSdJwt = credential.payload.decodeToString(),
                        requestedClaimPaths = match.requestedClaimPaths,
                        nonce = resolved.nonce,
                        audience = resolved.audience,
                        transactionData = resolved.transactionData,
                        deviceKeyAlias = credential.deviceKeyAlias,
                        pasoClaims = pasoClaims,
                    ),
                    authorizedSignature,
                )
            }

            Format.MsoMdoc -> {
                val decodedPayload =
                    android.util.Base64.decode(
                        credential.payload.decodeToString(),
                        android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
                    )
                // The SessionTranscript handover depends on the OID4VP delivery profile.
                //
                // - Deep-link (direct_post / direct_post.jwt): use `Oid4vpHandover`.
                //   `clientId` is the verbatim `client_id` from the Authorization Request —
                //   i.e. the **prefixed** form like `x509_hash:<thumbprint>`, which is exactly
                //   what `resolved.audience` already holds on this path (both are the Client
                //   Identifier per OpenID4VP 1.0); `Client.id` is used directly where available.
                //
                // - DC API (Android W3C Digital Credentials API): use `Oid4vpDcApiHandover`.
                //   The handover hashes `origin` instead of `clientId`/`responseUri`. We pass
                //   the unprefixed verifier origin captured during request resolution.
                val handover: MdocDeviceResponseBuilder.Handover =
                    if (resolved.dcApi != null) {
                        MdocDeviceResponseBuilder.Handover.DcApi(
                            origin = resolved.dcApi.verifierOrigin,
                        )
                    } else {
                        val mdocClientId =
                            resolved.resolvedRequest
                                ?.client
                                ?.id
                                ?.toString()
                                ?: resolved.audience
                        val responseUri =
                            resolved.responseUri
                                ?: error("Deep-link mDoc presentation requires response_uri")
                        MdocDeviceResponseBuilder.Handover.DeepLink(
                            clientId = mdocClientId,
                            responseUri = responseUri,
                        )
                    }
                // DCQL paths for mDoc are [namespace, element_name] per OID4VP §6.4.1, so
                // group the requested paths by namespace. The previous hardcoded
                // `org.iso.18013.5.1` only worked for ISO mDL credentials and silently
                // dropped every claim for credentials in other namespaces (e.g.
                // `eu.europa.ec.av.1`), leaving the verifier with an empty disclosure.
                val requestedNamespaces: Map<String, List<String>> =
                    match.requestedClaimPaths
                        .filter { it.size >= 2 }
                        .groupBy({ it[0] }, { it[1] })
                val device =
                    mdocBuilder.build(
                        MdocDeviceResponseBuilder.Input(
                            issuerSignedItemsCbor = decodedPayload,
                            requestedNamespaces = requestedNamespaces,
                            nonce = resolved.nonce,
                            handover = handover,
                            mdocGeneratedNonce = mdocGeneratedNonce,
                            transactionData = resolved.transactionData,
                            deviceKeyAlias = credential.deviceKeyAlias,
                            docType = credential.configurationId,
                        ),
                        authorizedSignature,
                    )
                android.util.Base64.encodeToString(
                    device,
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
                )
            }
        }

    private suspend fun buildPasoClaims(
        resolved: State.Resolved,
        pasoEntry: UiTransactionData,
    ): PasoScaClaims {
        val locale = LocaleApplier.effectiveLocale(settings.currentLanguagePreference())
        return PasoScaClaims(
            jti = UUID.randomUUID().toString(),
            responseMode = resolved.responseMode,
            displayLocale = locale.toLanguageTag(),
            // BiometricAuthorizer enforces BIOMETRIC_STRONG over a hardware-backed device
            // key — covers PSD2's possession (hwk) + inherence (bio_strong) categories.
            amr = listOf("hwk", "bio_strong"),
            transactionDataHash = UiTransactionData.hashEntry(pasoEntry.raw),
            transactionDataHashAlg = "sha-256",
            // PaSO §6.1: omitted when no signed credential metadata JWT is in use, which
            // is the case for this wallet today.
            metadataIntegrity = null,
            requestIntegrity =
                resolved.requestIntegrity
                    ?: error("requestIntegrity must be populated by attachVerifier when pasoEntry != null"),
            walletInstanceVersion = WalletInstance.version(),
        )
    }

    private fun randomBase64UrlNonce(byteLength: Int): String {
        val bytes = ByteArray(byteLength).also { SecureRandom().nextBytes(it) }
        return Base64URL.encode(bytes).toString()
    }

    private fun summarise(t: UiTransactionData): String =
        when (t) {
            is UiTransactionData.PaymentData -> "Payment: ${t.amount} ${t.currency} to ${t.payeeName ?: "(payee)"}"
            is UiTransactionData.PasoPayment -> "Payment: ${t.amountRaw} to ${t.payeeName}"
            is UiTransactionData.EudiScaPayment -> "Payment: ${t.amountDisplay} to ${t.payeeName}"
            is UiTransactionData.QesAuthorization -> "QES: ${t.documentDigests.size} document(s)"
            is UiTransactionData.Generic -> "Transaction: ${t.type}"
            is UiTransactionData.Invalid -> "Invalid transaction data (type=${t.type})"
        }

    private companion object {
        const val LOG_TAG = "PresentationClient"

        // OpenID4VP DC API profile protocol identifiers.
        const val PROTOCOL_OPENID4VP_SIGNED = "openid4vp-v1-signed"

        @Suppress("unused")
        const val PROTOCOL_OPENID4VP_UNSIGNED = "openid4vp-v1-unsigned"

        // Safety limit for envelope unwrap. Real DC API requests nest at most 3 levels
        // (top → requests[0] → data → request:<jws>); 6 leaves headroom for future shape.
        const val MAX_DC_API_UNWRAP_DEPTH = 6
    }
}
