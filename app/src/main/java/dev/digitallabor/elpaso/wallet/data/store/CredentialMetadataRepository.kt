package dev.digitallabor.elpaso.wallet.data.store

import android.util.Log
import dev.digitallabor.elpaso.wallet.data.network.HttpClientFactory
import dev.digitallabor.elpaso.wallet.data.settings.MetadataCacheTtl
import dev.digitallabor.elpaso.wallet.data.settings.SettingsRepository
import dev.digitallabor.elpaso.wallet.domain.model.Credential
import dev.digitallabor.elpaso.wallet.domain.model.CredentialMetadata
import dev.digitallabor.elpaso.wallet.domain.model.TransactionDataTypeMetadata
import dev.digitallabor.elpaso.wallet.issuance.CredentialMetadataClient
import dev.digitallabor.elpaso.wallet.issuance.CredentialMetadataVerifier
import com.nimbusds.jwt.SignedJWT
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.Locale

/**
 * Persists raw signed credential metadata JWTs (paso-proof-metadata.md §5) and
 * verifies them on read. Decoded forms are NEVER persisted.
 *
 * Reads return a verified [CredentialMetadata] (or null if no row matches / the
 * stored JWT fails verification at load time, per spec §5: "If a stored metadata
 * JWT fails verification upon loading … the Wallet SHALL discard it").
 */
class CredentialMetadataRepository(
    private val dao: CredentialMetadataDao,
    private val verifier: CredentialMetadataVerifier,
    private val client: CredentialMetadataClient,
    private val settings: SettingsRepository,
) {

    /**
     * Upsert with a wallet-side TTL cap. The stored `expiresAt` is reduced to
     * `min(jwtExp, now + ttl)` when the user has chosen a bounded TTL. When the
     * cache is disabled the call is dropped silently — readers also bypass the
     * DAO in that mode so no row would ever be returned.
     */
    suspend fun upsert(entity: CredentialMetadataEntity) {
        if (!settings.currentMetadataCacheEnabled()) return
        dao.upsert(applyTtlCap(entity))
    }

    /** Wipes every stored metadata row — used by the Settings UI's "Clear" action. */
    suspend fun clearAll() {
        dao.deleteAll()
    }

    private suspend fun applyTtlCap(entity: CredentialMetadataEntity): CredentialMetadataEntity {
        val ttl = settings.currentMetadataCacheTtl().durationMillis ?: return entity
        val capped = System.currentTimeMillis() + ttl
        return if (capped < entity.expiresAt) entity.copy(expiresAt = capped) else entity
    }

    /**
     * Looks up metadata for `credential`, preferring an exact locale match then
     * falling back through language → first available row. The returned
     * [CredentialMetadata] is always re-verified per spec §5.
     */
    suspend fun getVerifiedMetadata(credential: Credential, locale: Locale): CredentialMetadata? {
        // Cache disabled in settings → don't even look. getTransactionDataType falls
        // through to fetchAndVerifyOnDemand, which honours the same flag and skips
        // upsert so no row is persisted.
        if (!settings.currentMetadataCacheEnabled()) return null
        val rows = dao.forCredential(credential.id)
        if (rows.isEmpty()) return null
        val row = pickRow(rows, locale) ?: return null
        if (row.expiresAt <= System.currentTimeMillis()) {
            // TTL-capped or naturally expired — let the on-demand fetcher refresh it
            // and prune the stale row so we don't keep re-checking the same JWT.
            runCatching { dao.deleteForCredentialAndLocale(credential.id, row.locale) }
            return null
        }
        return verifier.verify(row.jwt, credential).fold(
            onSuccess = { it },
            onFailure = { failure ->
                Log.w(LOG_TAG, "Discarding invalid credential metadata for ${credential.id}@${row.locale}", failure)
                runCatching { dao.deleteForCredentialAndLocale(credential.id, row.locale) }
                null
            },
        )
    }

    /**
     * Convenience used by the consent UI: returns the [TransactionDataTypeMetadata]
     * for `requestedType` if present in the credential's verified metadata. If the
     * stored metadata doesn't cover `requestedType` (or no metadata is stored at
     * all), attempts a one-shot best-effort fetch + verify + upsert before giving up.
     *
     * Note on spec §7 unlinkability: this couples a metadata fetch to a presentation
     * moment. Acceptable for the demo's additive stance; production wallets should
     * pre-warm metadata for all credentials via the boot refresh path so this lazy
     * fetch never fires during a presentation.
     */
    suspend fun getTransactionDataType(
        credential: Credential,
        locale: Locale,
        requestedType: String,
    ): TransactionDataTypeMetadata? {
        getVerifiedMetadata(credential, locale)
            ?.transactionDataTypes
            ?.get(requestedType)
            ?.let { return it }
        return fetchAndVerifyOnDemand(credential, locale, requestedType)
    }

    private suspend fun fetchAndVerifyOnDemand(
        credential: Credential,
        locale: Locale,
        requestedType: String,
    ): TransactionDataTypeMetadata? = runCatching {
        val uri = client.discoverUri(credential.issuerId, credential.configurationId).getOrNull()
        if (uri == null) {
            Log.w(
                LOG_TAG,
                "lazy discovery for ${credential.id} found no credential_metadata_uri " +
                    "(issuer=${credential.issuerId}, type=${credential.configurationId})",
            )
            return@runCatching null
        }
        Log.i(LOG_TAG, "lazy discovery for ${credential.id} → $uri")
        val fetched = client.fetchJwt(uri, locale).getOrElse {
            Log.w(LOG_TAG, "lazy fetch for ${credential.id}@${locale.toLanguageTag()} failed", it)
            return@runCatching null
        }
        val verified = verifier.verify(fetched.rawJwt, credential).getOrElse {
            Log.w(LOG_TAG, "lazy verify for ${credential.id} failed", it)
            return@runCatching null
        }
        // Route through repository.upsert so the TTL cap and the cache-disabled
        // flag are honoured (it drops the write when caching is off).
        upsert(
            CredentialMetadataEntity(
                credentialId = credential.id,
                locale = locale.toLanguageTag(),
                jwt = fetched.rawJwt,
                expiresAt = verified.exp * 1000L,
                metadataUri = fetched.metadataUri,
            ),
        )
        Log.i(LOG_TAG, "lazy-fetched metadata for ${credential.id} (paso-view §7 unlinkability concession)")
        verified.transactionDataTypes[requestedType]
    }.getOrNull()

    suspend fun deleteForCredential(credentialId: String) {
        dao.deleteForCredential(credentialId)
    }

    /**
     * Union of every `transaction_data_types` key declared by any stored signed
     * credential metadata JWT. Used to populate the OpenID4VP library's exact-match
     * allowlist so verifier requests with new PaSO types don't bounce before the
     * wallet can render a consent screen.
     *
     * Payloads are decoded WITHOUT signature verification — this is a non-security
     * configuration step. The signed-and-verified read still happens later in
     * [getVerifiedMetadata] at consent time.
     */
    suspend fun knownTransactionDataTypes(): Set<String> = runCatching {
        dao.allJwts().flatMap { jwt ->
            runCatching {
                val payloadJson = SignedJWT.parse(jwt).payload.toString()
                val root = HttpClientFactory.json.parseToJsonElement(payloadJson).jsonObject
                val cm = root["credential_metadata"] as? JsonObject ?: return@runCatching emptyList()
                val types = cm["transaction_data_types"] as? JsonObject ?: return@runCatching emptyList()
                types.keys.toList()
            }.getOrDefault(emptyList())
        }.toSet()
    }.onFailure {
        Log.w(LOG_TAG, "knownTransactionDataTypes scan failed", it)
    }.getOrDefault(emptySet())

    private fun pickRow(
        rows: List<CredentialMetadataEntity>,
        locale: Locale,
    ): CredentialMetadataEntity? {
        if (rows.isEmpty()) return null
        val tag = locale.toLanguageTag()
        val lang = locale.language
        return rows.firstOrNull { it.locale.equals(tag, ignoreCase = true) }
            ?: rows.firstOrNull { it.locale.substringBefore('-').equals(lang, ignoreCase = true) }
            ?: rows.firstOrNull { it.locale.equals("en", ignoreCase = true) }
            ?: rows.first()
    }

    companion object {
        private const val LOG_TAG = "CredMetaRepo"
    }
}
