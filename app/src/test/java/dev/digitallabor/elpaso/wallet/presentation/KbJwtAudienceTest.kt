package dev.digitallabor.elpaso.wallet.presentation

import eu.europa.ec.eudi.openid4vp.Client
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.net.URI

/**
 * Regression guard for the KB-JWT `aud` value on the non-DC-API presentation paths.
 *
 * OpenID4VP 1.0 (SD-JWT VC Presentation Response, same wording for mdoc): "the `aud` claim
 * MUST be the value of the Client Identifier, except for requests over the DC API". The
 * Client Identifier *includes its prefix* — the spec's own example is
 * `x509_hash:Uvo3HtuIxuhC92rShpgqcT3YXwrqRxWEviRiA0OZszk`, and it states outright: "The
 * presentation would contain the full `verifier_attestation:example-client` string as the
 * audience … and the same full string would be used as the Client Identifier anywhere in
 * the OAuth flow."
 *
 * `PresentationClient` previously used `Client.clientId`, which is the *unprefixed*
 * `OriginalClientId`, producing verifier rejections of the form:
 *
 *     KB-JWT audience mismatch: presented "Z3j4NhyKI1zboNC6zyMyqD_5QXX_W77G9bdbqc61t_Y",
 *     expected one of ["x509_hash:Z3j4NhyKI1zboNC6zyMyqD_5QXX_W77G9bdbqc61t_Y"]
 *
 * These tests pin the `Client.id.clientId` contract that the fix relies on, so a library
 * upgrade that changes prefix handling fails here rather than in the field.
 */
class KbJwtAudienceTest {
    @Test
    fun `verifier_attestation client id keeps its prefix`() {
        val client = Client.VerifierAttestation("example-client")
        assertEquals("verifier_attestation:example-client", client.id.clientId)
        assertNotEquals(client.clientId, client.id.clientId)
    }

    @Test
    fun `redirect_uri client id keeps its prefix`() {
        val client = Client.RedirectUri(URI("https://verifier.example.com/cb"))
        assertEquals("redirect_uri:https://verifier.example.com/cb", client.id.clientId)
    }

    @Test
    fun `decentralized identifier client id keeps its prefix`() {
        val client = Client.DecentralizedIdentifier(URI("did:example:123"))
        assertEquals("decentralized_identifier:did:example:123", client.id.clientId)
    }

    @Test
    fun `pre-registered client id has no prefix and passes through verbatim`() {
        val client = Client.Preregistered("example-client", "Example Verifier")
        assertEquals("example-client", client.id.clientId)
        assertEquals(client.clientId, client.id.clientId)
    }
}
