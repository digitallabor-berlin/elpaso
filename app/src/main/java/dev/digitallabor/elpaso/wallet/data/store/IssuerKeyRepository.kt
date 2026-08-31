package dev.digitallabor.elpaso.wallet.data.store

import android.util.Log
import dev.digitallabor.elpaso.wallet.data.settings.SettingsRepository
import dev.digitallabor.elpaso.wallet.data.trust.IssuerKeySet
import java.time.Instant

/**
 * Persistence for issuer JWK sets.
 *
 * **Written regardless of the metadata-cache-enabled setting**, unlike
 * [CredentialMetadataRepository]. That setting governs credential *metadata*, which is
 * a privacy-optional convenience the wallet can do without — it falls back to hardcoded
 * renderers. An issuer key set is not optional: it is the anchor a key-set-mechanism
 * credential is verified against, and dropping it would turn a user's cache preference
 * into "this credential can no longer be verified". The TTL knob is still shared, since
 * the freshness question is genuinely the same one.
 *
 * Reads are the only place the key-set freshness policy lives: an expired row returns
 * null *and is pruned*, so a stale key is never handed to a verifier and the row does
 * not sit there being re-checked.
 */
class IssuerKeyRepository(
    private val dao: IssuerKeyDao,
    private val settings: SettingsRepository,
) {
    /**
     * The cached key set for [issuerId], or null when absent, expired or unparseable.
     * **Never fetches** — every caller that may run during a presentation goes through
     * `CacheOnlyIssuerKeySetResolver`, and this is the method that makes that honest.
     */
    suspend fun cached(
        issuerId: String,
        now: Instant = Instant.now(),
    ): IssuerKeySet? {
        val row = dao.forIssuer(issuerId) ?: return null
        if (row.expiresAt <= now.toEpochMilli()) {
            runCatching { dao.deleteForIssuer(issuerId) }
            Log.i(LOG_TAG, "pruned expired issuer key set for $issuerId")
            return null
        }
        return runCatching { toKeySet(row) }.getOrElse {
            Log.w(LOG_TAG, "stored issuer key set for $issuerId is unparseable; discarding", it)
            runCatching { dao.deleteForIssuer(issuerId) }
            null
        }
    }

    /** Stores [keySet], capping its expiry at the user's metadata-cache TTL. */
    suspend fun put(keySet: IssuerKeySet) {
        val ttl = settings.currentMetadataCacheTtl().durationMillis
        val fetchedAt = keySet.fetchedAt.toEpochMilli()
        dao.upsert(
            IssuerKeyEntity(
                issuerId = keySet.issuer,
                sourceUrl = keySet.sourceUrl,
                jwksJson = keySet.jwksJson,
                fetchedAt = fetchedAt,
                expiresAt = cappedExpiry(fetchedAt, ttl),
            ),
        )
    }

    /** Drops one issuer's cached set so the next resolve is a real fetch. */
    suspend fun invalidate(issuerId: String) {
        dao.deleteForIssuer(issuerId)
    }

    /** Wipes every stored key set — used by the Settings "Clear" action. */
    suspend fun clearAll() {
        dao.deleteAll()
    }

    /**
     * Issuers whose key set was fetched longer ago than [staleWindowMillis]. Drives the
     * boot-time refresh sweep. Keyed on `fetchedAt` rather than `expiresAt` because an
     * unbounded TTL leaves `expiresAt` at [Long.MAX_VALUE], which would otherwise mean
     * "never refresh".
     */
    suspend fun staleIssuers(
        now: Instant = Instant.now(),
        staleWindowMillis: Long,
    ): List<String> {
        val cutoff = now.toEpochMilli() - staleWindowMillis
        return dao.all().filter { it.fetchedAt < cutoff }.map { it.issuerId }
    }

    companion object {
        private const val LOG_TAG = "IssuerKeyRepo"

        /**
         * Pure expiry arithmetic. A null TTL is the "unbounded" choice in
         * [dev.digitallabor.elpaso.wallet.data.settings.MetadataCacheTtl] and yields no
         * expiry at all; refreshing such a row is the sweep's job, not the reader's.
         */
        internal fun cappedExpiry(
            fetchedAtMillis: Long,
            ttlMillis: Long?,
        ): Long = if (ttlMillis == null) Long.MAX_VALUE else fetchedAtMillis + ttlMillis

        /** Pure re-parse of a stored row. Throws if `jwksJson` is malformed or empty. */
        internal fun toKeySet(entity: IssuerKeyEntity): IssuerKeySet =
            IssuerKeySet.parse(
                issuer = entity.issuerId,
                jwksJson = entity.jwksJson,
                sourceUrl = entity.sourceUrl,
                fetchedAt = Instant.ofEpochMilli(entity.fetchedAt),
            )
    }
}
