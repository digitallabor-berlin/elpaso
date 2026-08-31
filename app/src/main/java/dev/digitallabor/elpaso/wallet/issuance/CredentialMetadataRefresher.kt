package dev.digitallabor.elpaso.wallet.issuance

import android.util.Log
import dev.digitallabor.elpaso.wallet.data.settings.LocaleApplier
import dev.digitallabor.elpaso.wallet.data.settings.SettingsRepository
import dev.digitallabor.elpaso.wallet.data.store.CredentialMetadataDao
import dev.digitallabor.elpaso.wallet.data.store.CredentialMetadataEntity
import dev.digitallabor.elpaso.wallet.data.store.CredentialMetadataRepository
import dev.digitallabor.elpaso.wallet.data.store.CredentialRepository
import dev.digitallabor.elpaso.wallet.data.store.IssuerKeyRepository
import dev.digitallabor.elpaso.wallet.data.trust.IssuerKeySetResolver
import kotlinx.coroutines.flow.first
import java.time.Instant

/**
 * Lightweight credential metadata renewal (paso-proof-metadata.md §7). Drops the
 * spec's full unlinkability requirements — that's a future addition — but
 * specifically does NOT refresh during a presentation flow: triggered only at app
 * launch from [dev.digitallabor.elpaso.wallet.ElPasoApp.onCreate], which is causally
 * independent from any verifier interaction.
 *
 * Refreshes only when a stored JWT is past `exp - 24h` (or already expired). The
 * Section 6 verification on read in [CredentialMetadataRepository] already discards
 * corrupted JWTs lazily.
 */
class CredentialMetadataRefresher(
    private val credentialRepository: CredentialRepository,
    private val metadataDao: CredentialMetadataDao,
    private val metadataRepository: CredentialMetadataRepository,
    private val client: CredentialMetadataClient,
    private val verifier: CredentialMetadataVerifier,
    private val settings: SettingsRepository,
    private val issuerKeyRepository: IssuerKeyRepository,
    private val issuerKeySetResolver: IssuerKeySetResolver,
) {
    /**
     * Walks all stored credentials and refreshes any metadata JWTs whose expiry is
     * within [staleWindowMillis] (default 24h). All errors are logged and swallowed.
     */
    suspend fun refreshStale(
        now: Instant = Instant.now(),
        staleWindowMillis: Long = STALE_WINDOW_MS,
    ) {
        // Key sets first, and unconditionally: they are a verification anchor rather than
        // a display convenience, so the metadata-cache toggle does not govern them. Doing
        // this at boot is what keeps presentation-time reads cache-only (spec §5.3).
        refreshStaleIssuerKeys(now, staleWindowMillis)
        runCatching {
            // Cache disabled in Settings → nothing to refresh. Reads bypass the DAO
            // entirely and upserts are dropped, so any leftover rows are unreachable.
            if (!settings.currentMetadataCacheEnabled()) {
                Log.d(LOG_TAG, "metadata cache disabled in settings — skipping refresh sweep")
                return@runCatching
            }
            val cutoff = now.toEpochMilli() + staleWindowMillis
            val userLocale = LocaleApplier.effectiveLocale(settings.currentLanguagePreference())
            val credentials = credentialRepository.observeAll().first()
            for (credential in credentials) {
                val rows = metadataDao.forCredential(credential.id)
                if (rows.isEmpty()) continue
                val stale = rows.filter { it.expiresAt < cutoff }
                if (stale.isEmpty()) continue
                val freshUri = rows.first().metadataUri
                for (row in stale) {
                    val locale =
                        runCatching { java.util.Locale.forLanguageTag(row.locale) }
                            .getOrNull() ?: userLocale
                    val fetched = client.fetchJwt(freshUri, locale).getOrNull() ?: continue
                    val verified = verifier.verify(fetched.rawJwt, credential).getOrNull() ?: continue
                    metadataRepository.upsert(
                        CredentialMetadataEntity(
                            credentialId = credential.id,
                            locale = row.locale,
                            jwt = fetched.rawJwt,
                            expiresAt = verified.exp * 1000L,
                            metadataUri = fetched.metadataUri,
                        ),
                    )
                }
            }
        }.onFailure {
            Log.w(LOG_TAG, "credential metadata refresh sweep threw", it)
        }
    }

    /**
     * Re-fetches every issuer key set older than [staleWindowMillis]. Runs at app launch
     * only, from `ElPasoApp.onCreate`, which is causally independent of any verifier
     * interaction — the same reason the metadata sweep lives here (paso-proof-metadata.md
     * §7/§8). Errors are logged and swallowed: a failed refresh leaves the previous set
     * in place until it expires, which is strictly better than dropping it.
     *
     * Each issuer is invalidated immediately before resolving, because
     * `CachingIssuerKeySetResolver` returns a cached-but-unexpired set and would
     * otherwise short-circuit the very refresh this sweep exists to perform.
     *
     * Note this runs outside the metadata-cache-enabled early return above: an issuer key
     * set is a verification anchor, not a display convenience (see [IssuerKeyRepository]).
     */
    private suspend fun refreshStaleIssuerKeys(
        now: Instant,
        staleWindowMillis: Long,
    ) {
        runCatching {
            val stale = issuerKeyRepository.staleIssuers(now, staleWindowMillis)
            if (stale.isEmpty()) return@runCatching
            Log.i(LOG_TAG, "refreshing ${stale.size} stale issuer key set(s)")
            for (issuerId in stale) {
                issuerKeyRepository.invalidate(issuerId)
                issuerKeySetResolver
                    .resolve(issuerId, now)
                    .onFailure { Log.w(LOG_TAG, "issuer key set refresh failed for $issuerId", it) }
            }
        }.onFailure {
            Log.w(LOG_TAG, "issuer key set refresh sweep threw", it)
        }
    }

    companion object {
        private const val LOG_TAG = "CredMetaRefresh"
        private const val STALE_WINDOW_MS = 24L * 60 * 60 * 1000
    }
}
