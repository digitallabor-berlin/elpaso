package dev.digitallabor.elpaso.wallet.presentation.txdata

import dev.digitallabor.elpaso.wallet.data.store.CredentialMetadataRepository
import dev.digitallabor.elpaso.wallet.domain.model.Credential
import dev.digitallabor.elpaso.wallet.domain.model.Format
import dev.digitallabor.elpaso.wallet.domain.model.TransactionDataTypeMetadata
import dev.digitallabor.elpaso.wallet.domain.model.UiLabels
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale

/**
 * Covers the precedence and failure policy of paso-proof-metadata.md §5.3/§5.4:
 * a verified ad-hoc JWT is authoritative for its entry, a failed one makes the entry
 * incompatible, and neither path may reach the stored-metadata channel.
 */
class TransactionMetadataResolverTest {
    private val locale: Locale = Locale.forLanguageTag("en-US")
    private val now: Instant = Instant.ofEpochSecond(1_760_000_000)

    private val credential =
        Credential(
            id = "sparkassen_auth",
            format = Format.SdJwtVc,
            configurationId = "https://creds.digitallabor.dev/vct/sparkassen_auth",
            issuerId = "https://foundry.digitallabor.dev",
            displayName = "Sparkassen Auth",
            displayMetadataJson = "{}",
            payload = ByteArray(0),
            deviceKeyAlias = "alias",
            issuedAt = now,
            expiresAt = null,
            lastUsedAt = null,
            usageCount = 0,
        )

    private fun metadata(title: String) =
        TransactionDataTypeMetadata(claims = emptyList(), uiLabels = UiLabels()).let {
            // Distinguishable instances; the title is only a marker for which channel won.
            it.copy(
                uiLabels =
                    UiLabels(
                        transactionTitle =
                            listOf(
                                dev.digitallabor.elpaso.wallet.domain.model
                                    .LocalizedLabel(locale = "en-US", value = title, valueType = null),
                            ),
                    ),
            )
        }

    private fun entry(
        key: String,
        type: String = "urn:paso:sca:dev.digitallabor:limitchange:1",
        adhocJwt: String? = null,
    ) = TransactionMetadataResolver.Entry(key = key, type = type, adhocMetadataJwt = adhocJwt)

    private fun titleOf(m: TransactionDataTypeMetadata) =
        m.uiLabels.transactionTitle
            .single()
            .value

    @Test
    fun withoutAnAdhocJwtItUsesTheStoredMetadataChannel() =
        runTest {
            val repo = mockk<CredentialMetadataRepository>()
            val verifier = mockk<AdhocTransactionMetadataVerifier>()
            coEvery { repo.getTransactionDataType(any(), any(), any()) } returns metadata("stored")

            val outcome = TransactionMetadataResolver(repo, verifier).resolve(listOf(entry("e1")), credential, locale, now)

            val resolved = outcome as TransactionMetadataResolver.Outcome.Resolved
            assertEquals("stored", titleOf(resolved.byEntry.getValue("e1")))
        }

    @Test
    fun aVerifiedAdhocJwtWinsAndNeverTouchesTheStoredChannel() =
        runTest {
            // §5.4: the ad-hoc metadata is used "in place of" the stored entry, and §5.4 also
            // forbids persisting it. Reaching the repository at all would trigger its lazy
            // fetch-and-upsert path — a network call mid-presentation (a §8 linkability
            // hazard) and a write of metadata this transaction is not entitled to store.
            val repo = mockk<CredentialMetadataRepository>()
            val verifier = mockk<AdhocTransactionMetadataVerifier>()
            coEvery { verifier.verify(any(), any(), any(), any()) } returns Result.success(metadata("adhoc"))

            val outcome =
                TransactionMetadataResolver(repo, verifier)
                    .resolve(listOf(entry("e1", adhocJwt = "jwt")), credential, locale, now)

            val resolved = outcome as TransactionMetadataResolver.Outcome.Resolved
            assertEquals("adhoc", titleOf(resolved.byEntry.getValue("e1")))
            coVerify(exactly = 0) { repo.getTransactionDataType(any(), any(), any()) }
        }

    @Test
    fun aFailedAdhocJwtMakesTheEntryIncompatibleWithNoFallback() =
        runTest {
            // §5.3: "The Wallet SHALL NOT fall back to the stored credential metadata entry
            // for a transaction_data entry whose metadata parameter fails verification."
            val repo = mockk<CredentialMetadataRepository>()
            val verifier = mockk<AdhocTransactionMetadataVerifier>()
            coEvery { verifier.verify(any(), any(), any(), any()) } returns
                Result.failure(IllegalStateException("ad-hoc metadata JWT signature verification failed"))

            val outcome =
                TransactionMetadataResolver(repo, verifier)
                    .resolve(listOf(entry("e1", adhocJwt = "jwt")), credential, locale, now)

            val incompatible = outcome as TransactionMetadataResolver.Outcome.Incompatible
            assertEquals("urn:paso:sca:dev.digitallabor:limitchange:1", incompatible.entryType)
            assertTrue(incompatible.reason.contains("signature"))
            coVerify(exactly = 0) { repo.getTransactionDataType(any(), any(), any()) }
        }

    @Test
    fun oneIncompatibleEntryCondemnsTheWholeRequest() =
        runTest {
            // The user consents once, to the request as a whole; rendering the good entries
            // and quietly dropping the unverifiable one would misrepresent what is approved.
            val repo = mockk<CredentialMetadataRepository>()
            val verifier = mockk<AdhocTransactionMetadataVerifier>()
            coEvery { repo.getTransactionDataType(any(), any(), any()) } returns metadata("stored")
            coEvery { verifier.verify(any(), any(), any(), any()) } returns Result.failure(IllegalStateException("nope"))

            val outcome =
                TransactionMetadataResolver(repo, verifier).resolve(
                    listOf(entry("good"), entry("bad", type = "urn:paso:sca:global:payment:1", adhocJwt = "jwt")),
                    credential,
                    locale,
                    now,
                )

            val incompatible = outcome as TransactionMetadataResolver.Outcome.Incompatible
            assertEquals("urn:paso:sca:global:payment:1", incompatible.entryType)
        }

    @Test
    fun anEntryWithNoMetadataInEitherChannelIsNotIncompatible() =
        runTest {
            // Absence is the ordinary case — most transaction_data types have no issuer
            // metadata at all and render through the hardcoded block. Only a present-but-
            // invalid `metadata` parameter is a failure.
            val repo = mockk<CredentialMetadataRepository>()
            val verifier = mockk<AdhocTransactionMetadataVerifier>()
            coEvery { repo.getTransactionDataType(any(), any(), any()) } returns null

            val outcome = TransactionMetadataResolver(repo, verifier).resolve(listOf(entry("e1")), credential, locale, now)

            val resolved = outcome as TransactionMetadataResolver.Outcome.Resolved
            assertTrue(resolved.byEntry.isEmpty())
        }

    @Test
    fun twoEntriesOfTheSameTypeKeepTheirOwnMetadata() =
        runTest {
            // §5.4 scopes ad-hoc metadata to "the enclosing transaction_data entry ... for
            // this transaction only". Keying by type instead of by entry would let one
            // entry's issuer-signed labels describe a sibling entry the issuer never signed for.
            val repo = mockk<CredentialMetadataRepository>()
            val verifier = mockk<AdhocTransactionMetadataVerifier>()
            coEvery { repo.getTransactionDataType(any(), any(), any()) } returns metadata("stored")
            coEvery { verifier.verify(any(), any(), any(), any()) } returns Result.success(metadata("adhoc"))

            val outcome =
                TransactionMetadataResolver(repo, verifier).resolve(
                    listOf(entry("plain"), entry("signed", adhocJwt = "jwt")),
                    credential,
                    locale,
                    now,
                )

            val resolved = outcome as TransactionMetadataResolver.Outcome.Resolved
            assertEquals("stored", titleOf(resolved.byEntry.getValue("plain")))
            assertEquals("adhoc", titleOf(resolved.byEntry.getValue("signed")))
        }
}
