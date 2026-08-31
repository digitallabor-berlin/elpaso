package dev.digitallabor.elpaso.wallet.data.trust

import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jwt.SignedJWT
import dev.digitallabor.elpaso.wallet.domain.model.IssuerBinding
import dev.digitallabor.elpaso.wallet.testing.TestCredentials
import dev.digitallabor.elpaso.wallet.testing.TestPki
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * paso-proof-metadata.md §7 step 6, key-set bullet: the metadata JWT "SHALL have been
 * verified per step 3 using a key from the same issuer key set that verifies the
 * credential itself".
 *
 * "The same key set" is the load-bearing phrase — not merely a key set belonging to the
 * same issuer. And the first check is which mechanism verified the credential, because
 * without it a verifier picks the binding rule by picking its own JWT's header (§10.2).
 */
class IssuerSignedJwtKeySetTest {
    private val now = TestPki.NOW
    private val sourceUrl = "https://issuer.example/.well-known/jwt-vc-issuer"
    private val otherSourceUrl = "https://issuer.example/keys.json"

    private val signingNode = TestPki.ca("CN=Key Set Signer")
    private val signingJwk = TestPki.jwk(signingNode, kid = "k1")
    private val strangerJwk = TestPki.jwk(TestPki.ca("CN=Stranger"), kid = "k2")

    private fun keySet(
        source: String = sourceUrl,
        vararg keys: JWK,
    ) = IssuerKeySet.parse(
        issuer = TestCredentials.ISSUER_ID,
        jwksJson = """{"keys":[${keys.joinToString(",") { it.toJSONString() }}]}""",
        sourceUrl = source,
        fetchedAt = now,
    )

    private fun credential(
        binding: IssuerBinding? = IssuerBinding.KeySet,
        keySetSource: String? = sourceUrl,
    ) = TestCredentials.sdJwt(
        issuerJwt = TestPki.jws(signingNode, "dc+sd-jwt", """{"iss":"${TestCredentials.ISSUER_ID}"}""", kid = "k1"),
        issuerBinding = binding,
        issuerKeySetSource = keySetSource,
    )

    private fun metadataJwt(
        signer: TestPki.Node = signingNode,
        kid: String? = "k1",
        withX5c: Boolean = false,
    ): SignedJWT {
        val root = TestPki.ca("CN=Irrelevant Root")
        return SignedJWT.parse(
            TestPki.jws(
                signer = signer,
                typ = "credential-metadata+jwt",
                payloadJson = """{"iss":"${TestCredentials.ISSUER_ID}"}""",
                chain = if (withX5c) TestPki.chain(TestPki.child("CN=Irrelevant Leaf", root), root) else null,
                kid = kid,
            ),
        )
    }

    private inline fun expectFailure(
        fragment: String,
        block: () -> Unit,
    ) {
        try {
            block()
            fail("expected IllegalStateException containing \"$fragment\"")
        } catch (e: IllegalStateException) {
            assertTrue("message was: ${e.message}", e.message!!.contains(fragment))
        }
    }

    @Test
    fun `binds when the credential and the JWT share a key set and a key`() {
        IssuerSignedJwt.bindToKeySet(
            signed = metadataJwt(),
            credential = credential(),
            keySet = keySet(sourceUrl, signingJwk),
            label = "L",
        )
    }

    @Test
    fun `binds when the JWT carries no kid, by trying every key`() {
        // §5.2 only RECOMMENDS a kid, so its absence is legitimate.
        IssuerSignedJwt.bindToKeySet(
            signed = metadataJwt(kid = null),
            credential = credential(),
            keySet = keySet(sourceUrl, strangerJwk, TestPki.jwk(signingNode)),
            label = "L",
        )
    }

    @Test
    fun `refuses a credential verified by x5c`() {
        // The §10.2 check, one level up. Without it, a verifier chooses the binding rule.
        expectFailure("was not verified by a key set") {
            IssuerSignedJwt.bindToKeySet(
                signed = metadataJwt(),
                credential = credential(binding = IssuerBinding.X5c, keySetSource = null),
                keySet = keySet(sourceUrl, signingJwk),
                label = "L",
            )
        }
    }

    @Test
    fun `refuses a credential with no recorded binding`() {
        expectFailure("was not verified by a key set") {
            IssuerSignedJwt.bindToKeySet(
                signed = metadataJwt(),
                credential = credential(binding = null, keySetSource = null),
                keySet = keySet(sourceUrl, signingJwk),
                label = "L",
            )
        }
    }

    @Test
    fun `refuses a key set from a different source URL`() {
        expectFailure("different issuer key set") {
            IssuerSignedJwt.bindToKeySet(
                signed = metadataJwt(),
                credential = credential(keySetSource = otherSourceUrl),
                keySet = keySet(sourceUrl, signingJwk),
                label = "L",
            )
        }
    }

    @Test
    fun `refuses a metadata JWT that carries an x5c header`() {
        expectFailure("mechanism confusion") {
            IssuerSignedJwt.bindToKeySet(
                signed = metadataJwt(withX5c = true),
                credential = credential(),
                keySet = keySet(sourceUrl, signingJwk),
                label = "L",
            )
        }
    }

    @Test
    fun `refuses a kid that names no key in the set`() {
        expectFailure("kid=missing") {
            IssuerSignedJwt.bindToKeySet(
                signed = metadataJwt(kid = "missing"),
                credential = credential(),
                keySet = keySet(sourceUrl, signingJwk),
                label = "L",
            )
        }
    }

    @Test
    fun `refuses a signature by a key outside the set`() {
        val stranger = TestPki.ca("CN=Outsider")
        expectFailure("verified against no key") {
            IssuerSignedJwt.bindToKeySet(
                signed = metadataJwt(signer = stranger, kid = null),
                credential = credential(),
                keySet = keySet(sourceUrl, signingJwk),
                label = "L",
            )
        }
    }
}
