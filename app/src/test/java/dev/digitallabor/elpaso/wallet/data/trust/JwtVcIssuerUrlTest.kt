package dev.digitallabor.elpaso.wallet.data.trust

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * draft-ietf-oauth-sd-jwt-vc-11 §5/§5.1: `/.well-known/jwt-vc-issuer` is inserted
 * *between* the host component and the path component, and a terminating `/` on the
 * path is removed first. This is not a suffix append.
 */
class JwtVcIssuerUrlTest {
    private fun ok(iss: String): String {
        val result = JwtVcIssuerUrl.of(iss)
        assertTrue("expected success for $iss, got ${result.exceptionOrNull()?.message}", result.isSuccess)
        return result.getOrThrow()
    }

    private fun rejected(
        iss: String,
        fragment: String,
    ) {
        val result = JwtVcIssuerUrl.of(iss)
        assertTrue("expected failure for $iss", result.isFailure)
        assertTrue(
            "message was: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()!!.message!!.contains(fragment),
        )
    }

    @Test
    fun `bare host`() {
        assertEquals("https://example.com/.well-known/jwt-vc-issuer", ok("https://example.com"))
    }

    @Test
    fun `bare host with terminating slash`() {
        assertEquals("https://example.com/.well-known/jwt-vc-issuer", ok("https://example.com/"))
    }

    @Test
    fun `host with explicit port`() {
        assertEquals("https://example.com:8443/.well-known/jwt-vc-issuer", ok("https://example.com:8443"))
    }

    @Test
    fun `tenant path is appended after the well-known segment`() {
        assertEquals(
            "https://example.com/.well-known/jwt-vc-issuer/tenant/1234",
            ok("https://example.com/tenant/1234"),
        )
    }

    @Test
    fun `tenant path with terminating slash drops the slash`() {
        assertEquals(
            "https://example.com/.well-known/jwt-vc-issuer/tenant/1234",
            ok("https://example.com/tenant/1234/"),
        )
    }

    @Test
    fun `http is rejected`() = rejected("http://example.com", "https")

    @Test
    fun `a query component is rejected`() = rejected("https://example.com?tenant=1", "query")

    @Test
    fun `a fragment is rejected`() = rejected("https://example.com#frag", "fragment")

    @Test
    fun `userinfo is rejected`() = rejected("https://user@example.com", "userinfo")

    @Test
    fun `a missing host is rejected`() = rejected("https:///path", "host")

    @Test
    fun `a non-URI string is rejected`() = rejected("not a uri at all", "")
}
