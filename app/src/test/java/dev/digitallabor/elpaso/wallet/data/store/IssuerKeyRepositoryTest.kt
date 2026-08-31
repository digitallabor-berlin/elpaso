package dev.digitallabor.elpaso.wallet.data.store

import dev.digitallabor.elpaso.wallet.data.settings.MetadataCacheTtl
import dev.digitallabor.elpaso.wallet.data.settings.SettingsRepository
import dev.digitallabor.elpaso.wallet.data.trust.IssuerKeySet
import dev.digitallabor.elpaso.wallet.testing.TestPki
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The key-set cache. Two properties matter beyond storage: the TTL cap reuses the
 * existing metadata-cache setting rather than adding a second knob (spec §5.3), and an
 * expired row is pruned on read so a stale key is never returned.
 */
class IssuerKeyRepositoryTest {
    private val now: Instant = TestPki.NOW
    private val issuerId = "https://issuer.example"
    private val sourceUrl = "https://issuer.example/.well-known/jwt-vc-issuer"
    private val key = TestPki.jwk(TestPki.ca("CN=Cached Key"), kid = "k1")
    private val jwksJson = """{"keys":[${key.toJSONString()}]}"""

    /** Minimal in-memory DAO; keeps the test free of Room and of a mocking framework. */
    private class FakeDao : IssuerKeyDao {
        val rows = mutableMapOf<String, IssuerKeyEntity>()

        override suspend fun forIssuer(issuerId: String): IssuerKeyEntity? = rows[issuerId]

        override suspend fun all(): List<IssuerKeyEntity> = rows.values.toList()

        override suspend fun upsert(entity: IssuerKeyEntity) {
            rows[entity.issuerId] = entity
        }

        override suspend fun deleteForIssuer(issuerId: String) {
            rows.remove(issuerId)
        }

        override suspend fun deleteAll() = rows.clear()
    }

    private fun settings(ttl: MetadataCacheTtl): SettingsRepository =
        mockk {
            coEvery { currentMetadataCacheTtl() } returns ttl
        }

    private fun keySet(fetchedAt: Instant = now) =
        IssuerKeySet.parse(
            issuer = issuerId,
            jwksJson = jwksJson,
            sourceUrl = sourceUrl,
            fetchedAt = fetchedAt,
        )

    @Test
    fun `a stored key set round-trips`() =
        runTest {
            val dao = FakeDao()
            val repository = IssuerKeyRepository(dao, settings(MetadataCacheTtl.entries.first()))
            repository.put(keySet())
            val cached = repository.cached(issuerId, now)
            assertEquals(issuerId, cached?.issuer)
            assertEquals(sourceUrl, cached?.sourceUrl)
            assertEquals("k1", cached?.keys?.single()?.keyID)
        }

    @Test
    fun `an expired row returns null and is pruned`() =
        runTest {
            val dao = FakeDao()
            dao.rows[issuerId] =
                IssuerKeyEntity(
                    issuerId = issuerId,
                    sourceUrl = sourceUrl,
                    jwksJson = jwksJson,
                    fetchedAt = now.toEpochMilli() - 10_000,
                    expiresAt = now.toEpochMilli() - 1,
                )
            val repository = IssuerKeyRepository(dao, settings(MetadataCacheTtl.entries.first()))
            assertNull(repository.cached(issuerId, now))
            assertTrue("expired row should have been pruned", dao.rows.isEmpty())
        }

    @Test
    fun `an absent issuer returns null without throwing`() =
        runTest {
            val repository = IssuerKeyRepository(FakeDao(), settings(MetadataCacheTtl.entries.first()))
            assertNull(repository.cached("https://unknown.example", now))
        }

    @Test
    fun `cappedExpiry with a bounded TTL is fetchedAt plus the TTL`() {
        assertEquals(1_000L + 500L, IssuerKeyRepository.cappedExpiry(1_000L, 500L))
    }

    @Test
    fun `cappedExpiry with an unbounded TTL never expires`() {
        assertEquals(Long.MAX_VALUE, IssuerKeyRepository.cappedExpiry(1_000L, null))
    }

    @Test
    fun `staleIssuers reports rows fetched longer ago than the window`() =
        runTest {
            val dao = FakeDao()
            dao.rows["https://fresh.example"] =
                IssuerKeyEntity(
                    issuerId = "https://fresh.example",
                    sourceUrl = sourceUrl,
                    jwksJson = jwksJson,
                    fetchedAt = now.toEpochMilli() - 1_000,
                    expiresAt = Long.MAX_VALUE,
                )
            dao.rows["https://stale.example"] =
                IssuerKeyEntity(
                    issuerId = "https://stale.example",
                    sourceUrl = sourceUrl,
                    jwksJson = jwksJson,
                    fetchedAt = now.toEpochMilli() - 100_000,
                    expiresAt = Long.MAX_VALUE,
                )
            val repository = IssuerKeyRepository(dao, settings(MetadataCacheTtl.entries.first()))
            assertEquals(
                listOf("https://stale.example"),
                repository.staleIssuers(now, staleWindowMillis = 50_000),
            )
        }

    @Test
    fun `invalidate drops one issuer so the next resolve is a real fetch`() =
        runTest {
            val dao = FakeDao()
            val repository = IssuerKeyRepository(dao, settings(MetadataCacheTtl.entries.first()))
            repository.put(keySet())
            assertTrue(dao.rows.containsKey(issuerId))
            repository.invalidate(issuerId)
            assertNull(repository.cached(issuerId, now))
        }
}
