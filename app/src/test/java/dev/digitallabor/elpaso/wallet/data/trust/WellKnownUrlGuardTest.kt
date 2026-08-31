package dev.digitallabor.elpaso.wallet.data.trust

import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * draft-ietf-oauth-sd-jwt-vc-11 §10.1. The guard must reject an internal target both
 * when it is written as a literal and when an external DNS name resolves to one — the
 * second case is why resolution is injected rather than performed by the system here.
 */
class WellKnownUrlGuardTest {
    /** For literal-address cases: consulting the resolver at all would be a bug. */
    private val explodingResolver = HostResolver { host -> error("resolver must not be consulted for $host") }

    private fun resolvingTo(vararg addresses: String) = HostResolver { addresses.map { InetAddress.getByName(it) } }

    private fun rejected(
        url: String,
        resolver: HostResolver,
        fragment: String,
    ) {
        val result = WellKnownUrlGuard.check(url, resolver)
        assertTrue("expected failure for $url", result.isFailure)
        assertTrue(
            "message was: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()!!.message!!.contains(fragment),
        )
    }

    @Test
    fun `an external name resolving to a public address passes`() {
        val result =
            WellKnownUrlGuard.check(
                "https://example.com/.well-known/jwt-vc-issuer",
                resolvingTo("93.184.216.34"),
            )
        assertTrue("${result.exceptionOrNull()?.message}", result.isSuccess)
    }

    @Test
    fun `a jwks_uri carrying a query is allowed`() {
        // §5 forbids a query on `iss`, not on `jwks_uri`. The guard must not over-reject.
        val result =
            WellKnownUrlGuard.check(
                "https://example.com/keys?set=current",
                resolvingTo("93.184.216.34"),
            )
        assertTrue("${result.exceptionOrNull()?.message}", result.isSuccess)
    }

    @Test
    fun `http is rejected`() = rejected("http://example.com/x", resolvingTo("93.184.216.34"), "https")

    @Test
    fun `an IPv4 loopback literal is rejected without resolving`() = rejected("https://127.0.0.1/x", explodingResolver, "internal address")

    @Test
    fun `an IPv6 loopback literal is rejected without resolving`() = rejected("https://[::1]/x", explodingResolver, "internal address")

    @Test
    fun `RFC 1918 literals are rejected`() {
        listOf("10.0.0.5", "172.16.3.4", "192.168.1.1").forEach {
            rejected("https://$it/x", explodingResolver, "internal address")
        }
    }

    @Test
    fun `the link-local metadata address is rejected`() = rejected("https://169.254.169.254/x", explodingResolver, "internal address")

    @Test
    fun `a unique-local IPv6 literal is rejected`() = rejected("https://[fd00::1]/x", explodingResolver, "internal address")

    @Test
    fun `a CGNAT literal is rejected`() = rejected("https://100.64.0.1/x", explodingResolver, "internal address")

    @Test
    fun `a bare hostname without a dot is rejected as an internal name`() =
        rejected("https://intranet/x", explodingResolver, "internal host name")

    @Test
    fun `localhost is rejected as an internal name`() = rejected("https://localhost/x", explodingResolver, "internal host name")

    @Test
    fun `an external name resolving to a private address is rejected`() =
        rejected("https://rebind.example.com/x", resolvingTo("10.1.2.3"), "internal address")

    @Test
    fun `an external name is rejected when ANY resolved address is internal`() =
        rejected("https://mixed.example.com/x", resolvingTo("93.184.216.34", "192.168.0.9"), "internal address")

    @Test
    fun `an external name that resolves to nothing is rejected`() =
        rejected("https://void.example.com/x", HostResolver { emptyList() }, "did not resolve")

    @Test
    fun `a resolver that throws is a rejection, not a pass`() =
        rejected("https://broken.example.com/x", HostResolver { error("SERVFAIL") }, "SERVFAIL")
}
