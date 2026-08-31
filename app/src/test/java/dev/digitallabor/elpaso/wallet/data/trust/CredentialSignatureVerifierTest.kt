package dev.digitallabor.elpaso.wallet.data.trust

import dev.digitallabor.elpaso.wallet.domain.model.Format
import dev.digitallabor.elpaso.wallet.testing.TestPki
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * draft-ietf-oauth-sd-jwt-vc-11 §3.5 and §10.2. The tests that matter most are the two
 * mechanism-confusion cases: an `x5c` header under key-set policy, and its absence under
 * x5c policy. Both must be rejections. If either becomes a fallback, §10.2 is broken and
 * whoever composes the JOSE header is choosing the verification method.
 */
class CredentialSignatureVerifierTest {
    private val now: Instant = TestPki.NOW
    private val issuerId = "https://issuer.example"

    private val root = TestPki.ca("CN=Issuer Root")
    private val leaf = TestPki.child("CN=Issuer Leaf", root)
    private val chain = TestPki.chain(leaf, root)

    private val keySetNode = TestPki.ca("CN=Key Set Signer")
    private val keySetJwk = TestPki.jwk(keySetNode, kid = "k1")
    private val sourceUrl = "https://issuer.example/.well-known/jwt-vc-issuer"

    private fun keySet(vararg keys: com.nimbusds.jose.jwk.JWK) =
        IssuerKeySet.parse(
            issuer = issuerId,
            jwksJson = """{"keys":[${keys.joinToString(",") { it.toJSONString() }}]}""",
            sourceUrl = sourceUrl,
            fetchedAt = now,
        )

    /** A resolver that always yields the same set; a failing one for the miss case. */
    private fun resolverOf(set: IssuerKeySet?) =
        object : IssuerKeySetResolver {
            override suspend fun resolve(
                issuerId: String,
                now: Instant,
            ): Result<IssuerKeySet> =
                set?.let { Result.success(it) }
                    ?: Result.failure(IllegalStateException("no cached issuer key set"))
        }

    private fun trustList(
        mechanism: SignatureMechanism?,
        issuerTrusted: Boolean = true,
        keyTrusted: Boolean = true,
    ): TrustListService =
        mockk {
            every { mechanismFor(any()) } returns mechanism
            every { isIssuerTrusted(any(), any()) } returns issuerTrusted
            every { isKeyTrusted(any(), any()) } returns keyTrusted
        }

    private fun sdJwtPayload(
        signer: TestPki.Node,
        withX5c: Boolean,
        kid: String? = null,
        iss: String? = issuerId,
    ): ByteArray {
        val claims =
            if (iss == null) {
                """{"vct":"https://vct.example/pid"}"""
            } else {
                """{"iss":"$iss","vct":"https://vct.example/pid"}"""
            }
        val jwt =
            TestPki.jws(
                signer = signer,
                typ = "dc+sd-jwt",
                payloadJson = claims,
                chain = if (withX5c) chain else null,
                kid = kid,
            )
        return "$jwt~".toByteArray()
    }

    private fun failureMessage(result: Result<*>): String {
        assertTrue("expected failure, got $result", result.isFailure)
        return result.exceptionOrNull()!!.message ?: ""
    }

    // ---- x5c policy ----

    @Test
    fun `x5c policy accepts a credential with a valid chain`() =
        runTest {
            val verifier = CredentialSignatureVerifier(trustList(SignatureMechanism.X5c), resolverOf(null))
            val result = verifier.verify(Format.SdJwtVc, sdJwtPayload(leaf, withX5c = true), issuerId, now)
            assertTrue("${result.exceptionOrNull()?.message}", result.isSuccess)
            val binding = result.getOrThrow()
            assertTrue(binding is VerifiedIssuerBinding.X5c)
            assertEquals(2, (binding as VerifiedIssuerBinding.X5c).chain.size)
        }

    @Test
    fun `x5c policy rejects a credential with NO x5c header — no key-set fallback`() =
        runTest {
            val verifier =
                CredentialSignatureVerifier(
                    trustList(SignatureMechanism.X5c),
                    // A resolver that WOULD succeed. If this test passes only because the
                    // resolver failed, the fallback exists and §10.2 is broken.
                    resolverOf(keySet(keySetJwk)),
                )
            val message =
                failureMessage(
                    verifier.verify(Format.SdJwtVc, sdJwtPayload(keySetNode, withX5c = false, kid = "k1"), issuerId, now),
                )
            assertTrue(message, message.contains("missing x5c header"))
        }

    @Test
    fun `x5c policy rejects an iss that disagrees with the issuer identifier`() =
        runTest {
            val verifier = CredentialSignatureVerifier(trustList(SignatureMechanism.X5c), resolverOf(null))
            val message =
                failureMessage(
                    verifier.verify(
                        Format.SdJwtVc,
                        sdJwtPayload(leaf, withX5c = true, iss = "https://other.example"),
                        issuerId,
                        now,
                    ),
                )
            assertTrue(message, message.contains("iss"))
        }

    @Test
    fun `x5c policy rejects an untrusted leaf fingerprint`() =
        runTest {
            val verifier =
                CredentialSignatureVerifier(
                    trustList(SignatureMechanism.X5c, issuerTrusted = false),
                    resolverOf(null),
                )
            val message =
                failureMessage(
                    verifier.verify(Format.SdJwtVc, sdJwtPayload(leaf, withX5c = true), issuerId, now),
                )
            assertTrue(message, message.contains("not trusted"))
        }

    // ---- key-set policy ----

    @Test
    fun `key-set policy accepts a credential whose kid names a published key`() =
        runTest {
            val verifier =
                CredentialSignatureVerifier(
                    trustList(SignatureMechanism.JwtVcIssuerMetadata),
                    resolverOf(keySet(keySetJwk)),
                )
            val result =
                verifier.verify(
                    Format.SdJwtVc,
                    sdJwtPayload(keySetNode, withX5c = false, kid = "k1"),
                    issuerId,
                    now,
                )
            assertTrue("${result.exceptionOrNull()?.message}", result.isSuccess)
            val binding = result.getOrThrow() as VerifiedIssuerBinding.KeySet
            assertEquals(sourceUrl, binding.sourceUrl)
            assertEquals(keySetJwk.computeThumbprint().toString(), binding.keyThumbprint)
        }

    @Test
    fun `key-set policy accepts a credential with no kid by trying every key`() =
        runTest {
            val decoy = TestPki.jwk(TestPki.ca("CN=Decoy"), kid = "decoy")
            val verifier =
                CredentialSignatureVerifier(
                    trustList(SignatureMechanism.JwtVcIssuerMetadata),
                    resolverOf(keySet(decoy, TestPki.jwk(keySetNode))),
                )
            val result =
                verifier.verify(
                    Format.SdJwtVc,
                    sdJwtPayload(keySetNode, withX5c = false),
                    issuerId,
                    now,
                )
            assertTrue("${result.exceptionOrNull()?.message}", result.isSuccess)
        }

    @Test
    fun `key-set policy rejects a credential that PRESENTS an x5c header`() =
        runTest {
            // The mechanism-confusion case. Under key-set policy an x5c header is not merely
            // ignored — it is a rejection, because accepting it would let a header choose the
            // mechanism (§10.2).
            val verifier =
                CredentialSignatureVerifier(
                    trustList(SignatureMechanism.JwtVcIssuerMetadata),
                    resolverOf(keySet(keySetJwk)),
                )
            val message =
                failureMessage(
                    verifier.verify(Format.SdJwtVc, sdJwtPayload(leaf, withX5c = true), issuerId, now),
                )
            assertTrue(message, message.contains("mechanism confusion"))
        }

    @Test
    fun `key-set policy rejects a kid that names no published key`() =
        runTest {
            val verifier =
                CredentialSignatureVerifier(
                    trustList(SignatureMechanism.JwtVcIssuerMetadata),
                    resolverOf(keySet(keySetJwk)),
                )
            val message =
                failureMessage(
                    verifier.verify(
                        Format.SdJwtVc,
                        sdJwtPayload(keySetNode, withX5c = false, kid = "unknown"),
                        issuerId,
                        now,
                    ),
                )
            assertTrue(message, message.contains("kid=unknown"))
        }

    @Test
    fun `key-set policy rejects a signature by a key outside the published set`() =
        runTest {
            val stranger = TestPki.ca("CN=Stranger")
            val verifier =
                CredentialSignatureVerifier(
                    trustList(SignatureMechanism.JwtVcIssuerMetadata),
                    resolverOf(keySet(keySetJwk)),
                )
            val message =
                failureMessage(
                    verifier.verify(Format.SdJwtVc, sdJwtPayload(stranger, withX5c = false), issuerId, now),
                )
            assertTrue(message, message.contains("no key in the issuer key set"))
        }

    @Test
    fun `key-set policy rejects an absent iss claim`() =
        runTest {
            val verifier =
                CredentialSignatureVerifier(
                    trustList(SignatureMechanism.JwtVcIssuerMetadata),
                    resolverOf(keySet(keySetJwk)),
                )
            val message =
                failureMessage(
                    verifier.verify(
                        Format.SdJwtVc,
                        sdJwtPayload(keySetNode, withX5c = false, kid = "k1", iss = null),
                        issuerId,
                        now,
                    ),
                )
            assertTrue(message, message.contains("iss"))
        }

    @Test
    fun `key-set policy rejects a key the trust list does not pin`() =
        runTest {
            val verifier =
                CredentialSignatureVerifier(
                    trustList(SignatureMechanism.JwtVcIssuerMetadata, keyTrusted = false),
                    resolverOf(keySet(keySetJwk)),
                )
            val message =
                failureMessage(
                    verifier.verify(Format.SdJwtVc, sdJwtPayload(keySetNode, withX5c = false, kid = "k1"), issuerId, now),
                )
            assertTrue(message, message.contains("not a trusted key"))
        }

    @Test
    fun `key-set policy rejects when the resolver cannot supply a set`() =
        runTest {
            val verifier =
                CredentialSignatureVerifier(
                    trustList(SignatureMechanism.JwtVcIssuerMetadata),
                    resolverOf(null),
                )
            val message =
                failureMessage(
                    verifier.verify(Format.SdJwtVc, sdJwtPayload(keySetNode, withX5c = false, kid = "k1"), issuerId, now),
                )
            assertTrue(message, message.contains("no cached issuer key set"))
        }

    // ---- policy absence and format ----

    @Test
    fun `an issuer absent from the trust list is rejected`() =
        runTest {
            val verifier = CredentialSignatureVerifier(trustList(mechanism = null), resolverOf(keySet(keySetJwk)))
            val message =
                failureMessage(
                    verifier.verify(Format.SdJwtVc, sdJwtPayload(leaf, withX5c = true), issuerId, now),
                )
            assertTrue(message, message.contains("declares no signature mechanism"))
        }

    @Test
    fun `mso_mdoc is refused rather than silently accepted`() =
        runTest {
            val verifier = CredentialSignatureVerifier(trustList(SignatureMechanism.X5c), resolverOf(null))
            val message =
                failureMessage(
                    verifier.verify(Format.MsoMdoc, "irrelevant".toByteArray(), issuerId, now),
                )
            assertTrue(message, message.contains("not yet implemented"))
        }
}
