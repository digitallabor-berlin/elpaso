package dev.digitallabor.elpaso.wallet.issuance

import android.content.Context
import android.net.Uri
import android.util.Log
import com.nimbusds.jose.jwk.Curve
import dev.digitallabor.elpaso.wallet.R
import dev.digitallabor.elpaso.wallet.data.crypto.KeyManager
import dev.digitallabor.elpaso.wallet.data.network.HttpClientFactory
import dev.digitallabor.elpaso.wallet.data.settings.LocaleApplier
import dev.digitallabor.elpaso.wallet.data.settings.SettingsRepository
import dev.digitallabor.elpaso.wallet.data.store.CredentialMetadataEntity
import dev.digitallabor.elpaso.wallet.data.store.CredentialMetadataRepository
import dev.digitallabor.elpaso.wallet.data.store.CredentialRepository
import dev.digitallabor.elpaso.wallet.data.trust.CredentialSignatureVerifier
import dev.digitallabor.elpaso.wallet.data.trust.TrustListService
import dev.digitallabor.elpaso.wallet.dcapi.DcRegistrySync
import dev.digitallabor.elpaso.wallet.domain.model.Credential
import dev.digitallabor.elpaso.wallet.domain.model.Format
import dev.digitallabor.elpaso.wallet.issuance.proofs.WalletProofsSigner
import dev.digitallabor.elpaso.wallet.session.BiometricSignAuthorizer
import dev.digitallabor.elpaso.wallet.vct.SdJwtVctExtractor
import dev.digitallabor.elpaso.wallet.vct.VctMetadata
import dev.digitallabor.elpaso.wallet.vct.VctMetadataClient
import eu.europa.ec.eudi.openid4vci.AuthorizationCode
import eu.europa.ec.eudi.openid4vci.AuthorizationRequestPrepared
import eu.europa.ec.eudi.openid4vci.AuthorizedRequest
import eu.europa.ec.eudi.openid4vci.CredentialConfiguration
import eu.europa.ec.eudi.openid4vci.CredentialConfigurationIdentifier
import eu.europa.ec.eudi.openid4vci.CredentialIssuerMetadataError
import eu.europa.ec.eudi.openid4vci.CredentialResponseEncryptionPolicy
import eu.europa.ec.eudi.openid4vci.DPoPUsage
import eu.europa.ec.eudi.openid4vci.EncryptionSupportConfig
import eu.europa.ec.eudi.openid4vci.Grants
import eu.europa.ec.eudi.openid4vci.IssuanceRequestPayload
import eu.europa.ec.eudi.openid4vci.Issuer
import eu.europa.ec.eudi.openid4vci.MsoMdocCredential
import eu.europa.ec.eudi.openid4vci.OpenId4VCIConfig
import eu.europa.ec.eudi.openid4vci.ProofsSpecification
import eu.europa.ec.eudi.openid4vci.SdJwtVcCredential
import eu.europa.ec.eudi.openid4vci.SubmissionOutcome
import eu.europa.ec.eudi.openid4vci.TxCodeInputMode
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * Drives the OpenID4VCI flow using the EUDI `eudi-lib-jvm-openid4vci-kt` library.
 *
 * State machine:
 *   Idle → Resolving → OfferResolved → AwaitingAuth → Issuing → Done   (on auth-code path)
 *   Idle → Resolving → OfferResolved → Issuing → Done                  (on pre-auth path)
 *
 * The wallet's signer is a [WalletProofsSigner] backed by per-credential Android Keystore
 * P-256 keys, plugged into the library as
 *   `ProofsSpecification.JwtProofs.NoKeyAttestation(walletSigner)`.
 */
class IssuanceClient(
    private val context: Context,
    private val keyManager: KeyManager,
    private val repository: CredentialRepository,
    private val httpClient: HttpClient,
    private val trustList: TrustListService,
    private val settings: SettingsRepository,
    private val dpopSigner: DPoPSigner,
    private val biometricAuthorizer: BiometricSignAuthorizer,
    private val vctMetadataClient: VctMetadataClient,
    private val dcRegistrySync: DcRegistrySync,
    private val credentialMetadataClient: CredentialMetadataClient,
    private val credentialMetadataVerifier: CredentialMetadataVerifier,
    private val credentialMetadataRepository: CredentialMetadataRepository,
    private val credentialSignatureVerifier: CredentialSignatureVerifier,
) {
    sealed interface State {
        data object Idle : State

        /**
         * An offer resolution is in flight.
         *
         * Distinct from [Idle] on purpose. The UI used to infer "resolving" from `Idle` plus the
         * presence of a deep-link offer, which made the absence of work indistinguishable from
         * work in progress: after a failed offer was dismissed the client went back to `Idle` and
         * the screen went on claiming to resolve something, forever, with no request to time out.
         */
        data object Resolving : State

        data class OfferResolved(
            val issuer: String,
            val configurations: List<OfferedCredential>,
            val grant: GrantOption,
            val trusted: Boolean,
        ) : State

        data class AwaitingAuth(
            val authUri: Uri,
        ) : State

        data class Failed(
            val message: String,
            val cause: Throwable? = null,
            val phase: Phase = Phase.Issuance,
        ) : State {
            /**
             * Which half of the flow broke. A single `ErrorModal` serves every [Failed] state,
             * so it needs this to pick an accurate headline: failing to *read* an offer is a
             * different story for the user than failing to *issue* an accepted credential.
             */
            enum class Phase { Offer, Issuance }
        }

        data object Issuing : State

        data class Done(
            val credentialIds: List<String>,
        ) : State
    }

    data class OfferedCredential(
        val configurationId: String,
        val format: Format,
        val displayName: String,
        val docTypeOrVct: String,
        /**
         * Issuer-supplied display block, serialized to the same shape as SD-JWT VC Type
         * Metadata so the consent UI and persisted credentials can share
         * [dev.digitallabor.elpaso.wallet.domain.model.CredentialDisplay] resolution. `"{}"` when the
         * issuer did not advertise any display info for this configuration.
         */
        val displayMetadataJson: String,
    )

    sealed interface GrantOption {
        data object AuthorizationCode : GrantOption

        data class PreAuthorized(
            val txCode: TxCodeRequirement?,
        ) : GrantOption
    }

    data class TxCodeRequirement(
        val inputMode: TxCodeInputMode,
        val length: Int?,
        val description: String?,
    )

    val state = MutableStateFlow<State>(State.Idle)

    private val config: OpenId4VCIConfig by lazy {
        OpenId4VCIConfig(
            clientId = CLIENT_ID,
            authFlowRedirectionURI = URI.create(REDIRECT_URI),
            encryptionSupportConfig =
                EncryptionSupportConfig(
                    ecKeyCurve = Curve.P_256,
                    rcaKeySize = 2048,
                    credentialResponseEncryptionPolicy = CredentialResponseEncryptionPolicy.SUPPORTED,
                ),
            // DPoP is sent whenever the AS advertises `dpop_signing_alg_values_supported`
            // (PASO requires it; EUDI ref-wallet also negotiates it).
            dPoPUsage = DPoPUsage.IfSupported(dpopSigner),
        )
    }

    private data class Session(
        val rawOffer: String,
        val issuer: Issuer,
        val configurations: List<OfferedCredential>,
        val selected: List<String> = emptyList(),
        val prepared: AuthorizationRequestPrepared? = null,
        val authorized: AuthorizedRequest? = null,
    )

    private var session: Session? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Fire-and-forget offer resolution that runs in an [IssuanceClient]-owned scope. Use this from
     * Compose entry points — calling [resolveOffer] directly from a `LaunchedEffect` ties the HTTP
     * call to the composition lifetime, so any recomposition that disposes the host composable
     * cancels the in-flight metadata fetch with `LeftCompositionCancellationException`.
     */
    fun resolveOfferAsync(uri: Uri): Job {
        // Published before the coroutine is dispatched, so no frame can observe Idle while a
        // resolve is already on its way.
        state.value = State.Resolving
        return scope.launch { resolveOffer(uri) }
    }

    suspend fun resolveOffer(uri: Uri): Result<State.OfferResolved> =
        runCatching {
            Log.i(LOG_TAG, "resolveOffer start: $uri")
            state.value = State.Resolving
            val raw = OfferHandler.parse(uri) ?: error("Unsupported offer URI: $uri")
            Log.i(LOG_TAG, "calling Issuer.make")
            val issuer = Issuer.make(config, raw, httpClient).getOrThrow()
            Log.i(LOG_TAG, "Issuer.make returned")

            val offer = issuer.credentialOffer
            val supported = offer.credentialIssuerMetadata.credentialConfigurationsSupported
            val configurations =
                offer.credentialConfigurationIdentifiers.map { id ->
                    val cfg = supported[id] ?: error("Issuer metadata missing configuration $id")
                    val displayJson = buildIssuerDisplayJson(cfg)
                    OfferedCredential(
                        configurationId = id.value,
                        format =
                            when (cfg) {
                                is MsoMdocCredential -> Format.MsoMdoc
                                is SdJwtVcCredential -> Format.SdJwtVc
                                else -> error("Unsupported credential format for $id (${cfg::class.simpleName})")
                            },
                        displayName =
                            cfg.credentialMetadata
                                ?.display
                                ?.firstOrNull()
                                ?.name ?: id.value,
                        docTypeOrVct =
                            when (cfg) {
                                is MsoMdocCredential -> cfg.docType
                                is SdJwtVcCredential -> cfg.type
                                else -> id.value
                            },
                        displayMetadataJson = displayJson,
                    )
                }

            session = Session(rawOffer = raw, issuer = issuer, configurations = configurations)
            val issuerId = offer.credentialIssuerIdentifier.toString()
            val grant = grantOption(offer.grants)
            val trusted = settings.developerMode.first() || trustList.isIssuerTrusted(issuerId)
            Log.i(LOG_TAG, "resolveOffer success: issuer=$issuerId configs=${configurations.size} grant=$grant trusted=$trusted")
            State.OfferResolved(issuerId, configurations, grant, trusted).also { state.value = it }
        }.onFailure {
            Log.w(LOG_TAG, "resolveOffer failed", it)
            state.value =
                State.Failed(
                    describeOfferFailure(uri, it, "Failed to resolve offer"),
                    it,
                    State.Failed.Phase.Offer,
                )
        }

    private fun grantOption(grants: Grants?): GrantOption {
        // Spec: when both grants are offered, the wallet SHOULD prefer pre-authorized.
        val preAuth = grants?.preAuthorizedCode()
        return if (preAuth != null) {
            GrantOption.PreAuthorized(
                txCode =
                    preAuth.txCode?.let {
                        TxCodeRequirement(it.inputMode, it.length, it.description)
                    },
            )
        } else {
            GrantOption.AuthorizationCode
        }
    }

    /**
     * Single entry point invoked by the UI after the user has chosen which configurations to
     * accept. Dispatches to the pre-authorized token path or the authorization-code browser
     * path based on the grant carried by the offer.
     */
    suspend fun acceptOffer(
        selectedConfigurationIds: List<String>,
        txCode: String? = null,
    ): Result<Unit> =
        runCatching {
            val s = session ?: error("No pending session — call resolveOffer first")
            session = s.copy(selected = selectedConfigurationIds)
            if (s.issuer.credentialOffer.grants
                    ?.preAuthorizedCode() != null
            ) {
                completeWithPreAuthorizedCode(txCode).getOrThrow()
            } else {
                prepareAuthorization(selectedConfigurationIds).getOrThrow()
            }
            Unit
        }

    suspend fun prepareAuthorization(selectedConfigurationIds: List<String>): Result<Uri> =
        runCatching {
            val s = session ?: error("No pending session — call resolveOffer first")
            val prepared = with(s.issuer) { prepareAuthorizationRequest().getOrThrow() }
            session = s.copy(selected = selectedConfigurationIds, prepared = prepared)
            val uri = Uri.parse(prepared.authorizationCodeURL.value.toString())
            state.value = State.AwaitingAuth(uri)
            uri
        }.onFailure {
            Log.w(LOG_TAG, "prepareAuthorization failed", it)
            state.value = State.Failed(it.message ?: "Failed to prepare authorization", it)
        }

    suspend fun completeWithAuthorizationCode(
        code: String,
        authState: String,
    ): Result<List<String>> =
        runCatching {
            val s = session ?: error("No pending session")
            val prepared = s.prepared ?: error("Authorization not prepared")
            state.value = State.Issuing

            val authorized =
                with(s.issuer) {
                    with(prepared) {
                        authorizeWithAuthorizationCode(AuthorizationCode(code), authState).getOrThrow()
                    }
                }
            session = s.copy(authorized = authorized)
            issueAll(s.issuer, authorized, s.selected, s.configurations)
        }.onFailure {
            Log.w(LOG_TAG, "completeWithAuthorizationCode failed", it)
            state.value = State.Failed(describeIssuanceFailure(it, "Issuance failed"), it)
        }

    suspend fun completeWithPreAuthorizedCode(txCode: String?): Result<List<String>> =
        runCatching {
            val s = session ?: error("No pending session")
            state.value = State.Issuing
            val authorized = with(s.issuer) { authorizeWithPreAuthorizationCode(txCode).getOrThrow() }
            session = s.copy(authorized = authorized)
            issueAll(s.issuer, authorized, s.selected.ifEmpty { s.configurations.map { it.configurationId } }, s.configurations)
        }.onFailure {
            Log.w(LOG_TAG, "completeWithPreAuthorizedCode failed", it)
            state.value = State.Failed(describeIssuanceFailure(it, "Issuance failed"), it)
        }

    private suspend fun issueAll(
        issuer: Issuer,
        initialAuthorized: AuthorizedRequest,
        selected: List<String>,
        configurations: List<OfferedCredential>,
    ): List<String> {
        var authorized = initialAuthorized
        val issued = mutableListOf<String>()
        for (configurationId in selected) {
            val cfg = configurations.first { it.configurationId == configurationId }
            val configId = CredentialConfigurationIdentifier(configurationId)
            // When the token endpoint returned `authorization_details` with credential_identifiers,
            // the library requires IdentifierBased payloads (one request per identifier).
            // Otherwise we fall back to a single ConfigurationBased request.
            val payloads =
                authorized.credentialIdentifiers
                    ?.get(configId)
                    ?.takeIf { it.isNotEmpty() }
                    ?.map { credId -> IssuanceRequestPayload.IdentifierBased(configId, credId) }
                    ?: listOf(IssuanceRequestPayload.ConfigurationBased(configId))

            for (payload in payloads) {
                authorized = issueOne(issuer, authorized, payload, cfg, cfg.docTypeOrVct, issued)
            }
        }
        session = session?.copy(authorized = authorized)
        state.value = State.Done(issued)
        return issued
    }

    private suspend fun issueOne(
        issuer: Issuer,
        authorized: AuthorizedRequest,
        payload: IssuanceRequestPayload,
        cfg: OfferedCredential,
        docTypeOrVct: String,
        issued: MutableList<String>,
    ): AuthorizedRequest {
        val credentialUuid = UUID.randomUUID().toString()
        val deviceKeyAlias = "cred_$credentialUuid"
        keyManager.createDeviceKey(deviceKeyAlias)

        val signer = WalletProofsSigner(keyManager, listOf(deviceKeyAlias), biometricAuthorizer)
        val proofSpec = ProofsSpecification.JwtProofs.NoKeyAttestation(signer)

        val (updatedAuth, outcome) =
            with(issuer) {
                with(authorized) { request(payload, proofSpec).getOrThrow() }
            }

        when (outcome) {
            is SubmissionOutcome.Success -> {
                val payloadBytes = encodeIssued(outcome)
                val issuerId = issuer.credentialOffer.credentialIssuerIdentifier.toString()

                // The gate. paso-proof-metadata.md §3 requires the wallet to reject an
                // issuance it cannot validate and tell the user; sd-jwt-vc §3.5 says an
                // SD-JWT VC whose verification key cannot be validated under a permitted
                // Issuer Signature Mechanism "MUST be rejected". Nothing is stored on
                // failure — deliberately not behind a flag (spec §10).
                //
                // mdoc is exempt for now and that asymmetry is real: an ISO 18013-5 MSO is
                // COSE_Sign1 over CBOR and is tracked as a follow-up (spec §5.6, §11).
                val binding =
                    if (cfg.format == Format.SdJwtVc) {
                        credentialSignatureVerifier
                            .verify(cfg.format, payloadBytes, issuerId, Instant.now())
                            .getOrElse { cause ->
                                throw CredentialSignatureRejected(
                                    reason = cause.message ?: cause::class.java.simpleName,
                                    cause = cause,
                                )
                            }
                    } else {
                        Log.w(
                            LOG_TAG,
                            "storing ${cfg.format} credential from $issuerId WITHOUT issuer-signature " +
                                "verification — MSO COSE verification is not implemented",
                        )
                        null
                    }

                val (displayMetadataJson, resolvedDisplayName) = resolveDisplayMetadata(cfg, payloadBytes)
                val credential =
                    Credential(
                        id = credentialUuid,
                        format = cfg.format,
                        configurationId = docTypeOrVct,
                        issuerId = issuerId,
                        displayName = resolvedDisplayName,
                        displayMetadataJson = displayMetadataJson,
                        payload = payloadBytes,
                        deviceKeyAlias = deviceKeyAlias,
                        issuedAt = Instant.now(),
                        expiresAt = null,
                        lastUsedAt = null,
                        usageCount = 0,
                        issuerBinding = binding?.binding,
                        issuerKeySetSource = binding?.keySetSource,
                    )
                repository.insert(credential)
                issued += credentialUuid
                // Push to the DC API registry now so the new credential is matchable
                // before the user even sees the "issued" confirmation. The flow-based
                // listener in DcRegistrySync would catch up after a 250 ms debounce —
                // this avoids that race for an immediate verifier callback.
                runCatching { dcRegistrySync.registerNow() }
                    .onFailure { Log.w(LOG_TAG, "DC API registry update after issuance failed", it) }
                fetchAndStoreCredentialMetadata(credential)
            }

            is SubmissionOutcome.Deferred -> {
                error("Deferred issuance not yet implemented (txId=${outcome.transactionId})")
            }

            is SubmissionOutcome.Failed -> {
                keyManager.deleteDeviceKey(deviceKeyAlias)
                throw outcome.error
            }
        }
        return updatedAuth
    }

    /**
     * Resolves the display metadata JSON + display name to persist with a freshly-issued
     * credential.
     *
     * - For mso_mdoc we have no VCT URL, so the issuer-metadata-derived JSON captured at
     *   offer resolution time ([OfferedCredential.displayMetadataJson]) is what we keep.
     * - For SD-JWT VC we prefer the credential's own Type Metadata document fetched from
     *   its `vct` URL — it overrides whatever the issuer advertised. If the fetch fails
     *   (network error, non-http vct, malformed body) we fall back to the offer-time JSON
     *   so the card still picks up issuer-supplied colors/logos.
     */
    private suspend fun resolveDisplayMetadata(
        cfg: OfferedCredential,
        payloadBytes: ByteArray,
    ): Pair<String, String> {
        val offerJson = cfg.displayMetadataJson
        val locale = java.util.Locale.getDefault()
        if (cfg.format != Format.SdJwtVc) {
            return offerJson to pickName(offerJson, cfg.displayName, locale)
        }
        val vctUrl = SdJwtVctExtractor.extract(payloadBytes) ?: cfg.docTypeOrVct
        if (!vctUrl.startsWith("http://") && !vctUrl.startsWith("https://")) {
            return offerJson to pickName(offerJson, cfg.displayName, locale)
        }
        val metadata =
            vctMetadataClient.fetch(vctUrl).getOrNull()
                ?: return offerJson to pickName(offerJson, cfg.displayName, locale)
        val json =
            runCatching {
                HttpClientFactory.json.encodeToString(VctMetadata.serializer(), metadata)
            }.getOrNull() ?: return offerJson to pickName(offerJson, cfg.displayName, locale)
        val tag = locale.toLanguageTag()
        val lang = locale.language
        val display =
            metadata.display.firstOrNull { it.locale.equals(tag, ignoreCase = true) }
                ?: metadata.display.firstOrNull { it.locale?.substringBefore('-').equals(lang, ignoreCase = true) }
                ?: metadata.display.firstOrNull { it.locale.isNullOrBlank() }
                ?: metadata.display.firstOrNull()
        val name = display?.name ?: metadata.name ?: cfg.displayName
        return json to name
    }

    private fun pickName(
        metadataJson: String,
        fallback: String,
        locale: java.util.Locale,
    ): String =
        dev.digitallabor.elpaso.wallet.domain.model.CredentialDisplay
            .resolve(metadataJson, fallback, locale)
            .name

    private fun buildIssuerDisplayJson(cfg: CredentialConfiguration): String =
        IssuerDisplayJsonBuilder.build(
            cfg.credentialMetadata?.display.orEmpty(),
            cfg.credentialMetadata?.claims.orEmpty(),
        )

    /**
     * Best-effort fetch of the PaSO signed credential metadata (paso-proof-metadata.md
     * §2/§4) for [credential]. Stores the verified raw JWT for the user's current
     * locale plus an `en` fallback. All errors are logged and swallowed — the wallet
     * remains usable even when the issuer doesn't advertise `credential_metadata_uri`
     * or the metadata fails to verify.
     */
    private suspend fun fetchAndStoreCredentialMetadata(credential: Credential) {
        runCatching {
            val uri =
                credentialMetadataClient
                    .discoverUri(credential.issuerId, credential.configurationId)
                    .getOrNull() ?: return
            val userLocale = LocaleApplier.effectiveLocale(settings.currentLanguagePreference())
            val locales = linkedSetOf(userLocale)
            if (userLocale.language != "en") locales += java.util.Locale.ENGLISH
            for (locale in locales) {
                val fetchResult = credentialMetadataClient.fetchJwt(uri, locale)
                val fetched = fetchResult.getOrNull()
                if (fetched == null) {
                    Log.w(
                        LOG_TAG,
                        "credential_metadata_uri fetch failed for ${credential.id}@${locale.toLanguageTag()}",
                        fetchResult.exceptionOrNull(),
                    )
                    continue
                }
                val verifyResult = credentialMetadataVerifier.verify(fetched.rawJwt, credential)
                val verified = verifyResult.getOrNull()
                if (verified == null) {
                    Log.w(
                        LOG_TAG,
                        "credential metadata verification failed for ${credential.id}",
                        verifyResult.exceptionOrNull(),
                    )
                    continue
                }
                credentialMetadataRepository.upsert(
                    CredentialMetadataEntity(
                        credentialId = credential.id,
                        locale = locale.toLanguageTag(),
                        jwt = fetched.rawJwt,
                        expiresAt = verified.exp * 1000L,
                        metadataUri = fetched.metadataUri,
                    ),
                )
            }
        }.onFailure {
            Log.w(LOG_TAG, "fetchAndStoreCredentialMetadata threw for ${credential.id}", it)
        }
    }

    private fun encodeIssued(outcome: SubmissionOutcome.Success): ByteArray {
        val first = outcome.credentials.first().credential
        return when (first) {
            is eu.europa.ec.eudi.openid4vci.Credential.Str -> first.value.toByteArray(Charsets.UTF_8)
            is eu.europa.ec.eudi.openid4vci.Credential.Json -> first.value.toString().toByteArray(Charsets.UTF_8)
        }
    }

    fun reset() {
        session = null
        state.value = State.Idle
    }

    private fun describeIssuanceFailure(
        cause: Throwable,
        fallback: String,
    ): String {
        val captured = HttpClientFactory.consumeLastErrorBody()
        val chain = cause.causalChain().toList()
        // A rejected signature is our own decision, not a server error, so it gets a
        // localised sentence rather than whatever the library said. Placed after
        // consumeLastErrorBody() so that side effect still runs exactly once per failure.
        chain.firstNotNullOfOrNull { it as? CredentialSignatureRejected }?.let { rejection ->
            return context.getString(R.string.issue_error_credential_signature, rejection.reason)
        }
        val oauthError =
            chain.firstNotNullOfOrNull {
                (it as? eu.europa.ec.eudi.openid4vci.CredentialIssuanceError.AccessTokenRequestFailed)
            }
        if (oauthError != null) {
            return buildString {
                append("Token request failed: ${oauthError.error}")
                oauthError.errorDescription?.let { append(" — $it") }
                if (captured != null) append(" [server body: ${captured.body.take(400)}]")
            }
        }
        // Fallback for the kotlinx MissingFieldException path (body wasn't JSON or didn't
        // match any known wrapper shape, so the OkHttp interceptor couldn't rewrite it).
        if (captured != null && chain.any { it.isMissingErrorFieldException() }) {
            return "Issuer returned ${captured.status} from ${captured.url} with a non-OAuth body: " +
                captured.body.take(400)
        }
        return cause.message ?: fallback
    }

    /**
     * Turns a failed offer resolution into something actionable.
     *
     * `Issuer.make` parses `credential_configurations_supported` eagerly, so one malformed entry
     * rejects the entire metadata document — and the resulting `JsonDecodingException` identifies
     * only a JSON-path fragment (`$.0`), never the configuration at fault. When that is what
     * happened, re-fetch the document and name the offenders via [IssuerMetadataDiagnostics].
     *
     * The extra request only ever happens on a path that has already failed, and its result is
     * used solely for this message and the log. Nothing here repairs or re-interprets what the
     * issuer sent: a non-conformant issuer still fails, it just fails legibly.
     */
    private suspend fun describeOfferFailure(
        uri: Uri,
        cause: Throwable,
        fallback: String,
    ): String {
        // NOT causalChain(): the offer path wraps its real failure in a `reason` field rather
        // than in `cause`, so a plain cause walk sees only a message-less shell. See
        // OfferFailureChain.kt.
        val chain = cause.offerFailureChain()
        val rootMessage = cause.deepestMessage() ?: fallback

        val unparseableMetadata =
            chain.any { it is CredentialIssuerMetadataError.NonParseableCredentialIssuerMetadata }
        if (!unparseableMetadata) return rootMessage

        val findings =
            OfferHandler
                .extractIssuer(uri)
                // extractIssuer yields a bare host for by-reference offers, which is not a
                // resolvable base URL; only the by-value form gives us the issuer identifier.
                ?.takeIf { it.startsWith("https://") }
                ?.let { fetchIssuerMetadataDocument(it) }
                ?.let { IssuerMetadataDiagnostics.diagnose(it) }
                .orEmpty()

        if (findings.isEmpty()) {
            return "Issuer metadata is not valid OpenID4VCI 1.0: $rootMessage"
        }
        findings.forEach { Log.w(LOG_TAG, "issuer metadata defect — ${it.render()}") }
        return buildString {
            append("Issuer metadata is not valid OpenID4VCI 1.0. ")
            append(findings.joinToString("; ") { it.render() })
            append(". One malformed configuration rejects the whole metadata document, so every ")
            append("credential from this issuer is affected. Underlying parser error: $rootMessage")
        }
    }

    private suspend fun fetchIssuerMetadataDocument(issuerId: String): String? =
        runCatching {
            httpClient
                .get("${issuerId.trimEnd('/')}/.well-known/openid-credential-issuer")
                .bodyAsText()
        }.onFailure {
            Log.w(LOG_TAG, "diagnostic metadata re-fetch failed for $issuerId", it)
        }.getOrNull()

    private fun Throwable.causalChain(): Sequence<Throwable> = generateSequence(this) { it.cause }

    private fun Throwable.isMissingErrorFieldException(): Boolean {
        val name = this::class.qualifiedName.orEmpty()
        val msg = message.orEmpty()
        return (name.contains("JsonConvertException") || name.contains("MissingFieldException")) &&
            msg.contains("TokenResponseTO.Failure") &&
            msg.contains("'error'")
    }

    companion object {
        const val REDIRECT_URI = "elpaso://oauth/callback"
        const val CLIENT_ID = "elpaso-wallet"
        private const val LOG_TAG = "IssuanceClient"
    }
}
