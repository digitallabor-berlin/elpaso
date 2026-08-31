package dev.digitallabor.elpaso.wallet.data.trust

import android.util.Log
import dev.digitallabor.elpaso.wallet.data.store.IssuerKeyRepository
import java.time.Instant

/**
 * Cache first, then fetch. Injected **only** where a network call is causally
 * independent of any verifier interaction: issuance, and the boot-time refresh sweep.
 */
class CachingIssuerKeySetResolver(
    private val cache: IssuerKeyRepository,
    private val client: JwtVcIssuerMetadataClient,
) : IssuerKeySetResolver {
    override suspend fun resolve(
        issuerId: String,
        now: Instant,
    ): Result<IssuerKeySet> {
        cache.cached(issuerId, now)?.let { return Result.success(it) }
        return client.fetch(issuerId, now).onSuccess { keySet ->
            runCatching { cache.put(keySet) }
                .onFailure { Log.w(LOG_TAG, "failed to cache issuer key set for $issuerId", it) }
        }
    }

    private companion object {
        const val LOG_TAG = "CachingKeySetRes"
    }
}

/**
 * Cache only. A miss is a failure, **not** a fetch.
 *
 * This is the presentation-side resolver, and the reason it is a separate type rather
 * than a boolean parameter: paso-proof-metadata.md §8 treats a network call correlated
 * with a presentation as a linkability hazard, so the code path that runs at consent
 * time should not be *able* to make one. Anything constructed with this resolver
 * cannot, whatever a future caller passes it.
 *
 * The consequence is deliberate and documented in spec §6: a cache miss at consent time
 * fails metadata verification. The stored-metadata channel then degrades to the
 * hardcoded renderer as it does today; an ad-hoc JWT becomes `Outcome.Incompatible`
 * per §5.3.
 */
class CacheOnlyIssuerKeySetResolver(
    private val cache: IssuerKeyRepository,
) : IssuerKeySetResolver {
    override suspend fun resolve(
        issuerId: String,
        now: Instant,
    ): Result<IssuerKeySet> {
        val cached = cache.cached(issuerId, now)
        return if (cached != null) {
            Result.success(cached)
        } else {
            Result.failure(
                IllegalStateException(
                    "no cached issuer key set for $issuerId; refusing to fetch during a presentation",
                ),
            )
        }
    }
}
