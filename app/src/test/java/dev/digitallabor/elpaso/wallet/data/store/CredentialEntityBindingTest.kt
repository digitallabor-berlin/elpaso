package dev.digitallabor.elpaso.wallet.data.store

import dev.digitallabor.elpaso.wallet.domain.model.IssuerBinding
import dev.digitallabor.elpaso.wallet.testing.TestCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The recorded issuer binding must survive the Room round trip verbatim: it is what
 * paso-proof-metadata.md §7 step 6 dispatches on, so a value lost in mapping would
 * silently reopen the mechanism-confusion hole the policy exists to close.
 */
class CredentialEntityBindingTest {
    @Test
    fun `a key-set binding round-trips through the entity`() {
        val credential =
            TestCredentials.sdJwt(
                issuerJwt = "header.payload.signature",
                issuerBinding = IssuerBinding.KeySet,
                issuerKeySetSource = "https://issuer.example/.well-known/jwt-vc-issuer",
            )
        val restored = CredentialEntity.fromDomain(credential).toDomain()
        assertEquals(IssuerBinding.KeySet, restored.issuerBinding)
        assertEquals("https://issuer.example/.well-known/jwt-vc-issuer", restored.issuerKeySetSource)
    }

    @Test
    fun `an x5c binding round-trips with a null key-set source`() {
        val credential =
            TestCredentials.sdJwt(
                issuerJwt = "header.payload.signature",
                issuerBinding = IssuerBinding.X5c,
            )
        val restored = CredentialEntity.fromDomain(credential).toDomain()
        assertEquals(IssuerBinding.X5c, restored.issuerBinding)
        assertNull(restored.issuerKeySetSource)
    }

    @Test
    fun `a legacy row with no binding restores as null rather than defaulting`() {
        // Rows written before the issuance gate existed have no recorded mechanism. They
        // must NOT default to x5c: §7 step 6 would then apply the certificate binding
        // rule to a credential nothing ever verified.
        val entity =
            CredentialEntity.fromDomain(
                TestCredentials.sdJwt(issuerJwt = "header.payload.signature"),
            )
        assertNull(entity.issuerBinding)
        assertNull(entity.toDomain().issuerBinding)
    }

    @Test
    fun `fromWire rejects an unknown value`() {
        assertEquals(IssuerBinding.X5c, IssuerBinding.fromWire("x5c"))
        assertEquals(IssuerBinding.KeySet, IssuerBinding.fromWire("key_set"))
        assertNull(IssuerBinding.fromWire("did"))
        assertNull(IssuerBinding.fromWire(null))
    }
}
