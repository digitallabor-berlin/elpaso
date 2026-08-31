package dev.digitallabor.elpaso.wallet.data.trust

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * DNS resolution, injectable so [WellKnownUrlGuard] is testable without a network and
 * without a real DNS answer. Implementations may throw; the guard treats a throw as a
 * rejection.
 */
fun interface HostResolver {
    fun resolve(host: String): List<InetAddress>
}

/**
 * The SSRF guard of draft-ietf-oauth-sd-jwt-vc-11 §10.1, applied to every URL derived
 * from an issuer-supplied value: the `/.well-known/jwt-vc-issuer` URL and, separately,
 * any `jwks_uri` the configuration document names.
 *
 * §10.1 requires the wallet to validate that the URL is HTTPS and "does not address an
 * internal service by IP address or an internal host name", and that "if an external
 * DNS name is used, the resolved DNS name does not point to an internal IPv4 or IPv6
 * address". So there are two distinct checks and both are needed: an attacker who
 * cannot write `127.0.0.1` can still publish a DNS name that resolves to it.
 *
 * Resolution happens **before** the request, and the address set is checked in full —
 * a name is rejected if *any* of its addresses is internal, not merely the first.
 * (That does not close the DNS-rebinding window between this check and the socket
 * connect; doing so needs a pinned-address HTTP client, which is out of scope here and
 * noted in the spec's follow-ups.)
 *
 * Unlike `iss`, a `jwks_uri` may legitimately carry a query, so this guard does not
 * reject one — `JwtVcIssuerUrl` enforces the `iss`-specific restrictions.
 */
object WellKnownUrlGuard {
    val SYSTEM_RESOLVER: HostResolver = HostResolver { host -> InetAddress.getAllByName(host).toList() }

    fun check(
        url: String,
        resolver: HostResolver = SYSTEM_RESOLVER,
    ): Result<Unit> =
        runCatching {
            val uri = URI(url)
            check(uri.scheme?.lowercase() == "https") { "well-known URL must use https, got scheme=${uri.scheme}" }
            val host = uri.host
            check(!host.isNullOrBlank()) { "well-known URL has no host component: $url" }

            val literal = parseIpLiteral(host)
            if (literal != null) {
                check(!isInternal(literal)) { "well-known URL targets an internal address: $host" }
                return@runCatching
            }

            // A name with no dot cannot be a public FQDN; §10.1's "internal host name".
            check(host.contains('.')) { "well-known URL targets an internal host name: $host" }

            val resolved = resolver.resolve(host)
            check(resolved.isNotEmpty()) { "well-known URL host did not resolve: $host" }
            resolved.forEach { address ->
                check(!isInternal(address)) {
                    "well-known URL host $host resolves to an internal address: ${address.hostAddress}"
                }
            }
        }

    /**
     * Recognises a host component that is already an IP address, so no DNS lookup is
     * performed for it. `URI.getHost` keeps the brackets on an IPv6 literal, so they
     * are stripped first. Returns null for anything that is not a literal.
     */
    private fun parseIpLiteral(host: String): InetAddress? {
        val bare = host.removeSurrounding("[", "]")
        val looksNumeric = bare.contains(':') || bare.matches(IPV4_LITERAL)
        if (!looksNumeric) return null
        // For a literal, getByName performs no name resolution.
        return runCatching { InetAddress.getByName(bare) }.getOrNull()
    }

    /**
     * Whether [address] belongs to a range the wallet must never reach. Covers the
     * platform predicates plus two ranges they miss: IPv6 unique-local (`fc00::/7`,
     * which `isSiteLocalAddress` does not report) and IPv4 CGNAT (`100.64.0.0/10`).
     */
    internal fun isInternal(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        val bytes = address.address
        if (address is Inet6Address) {
            // fc00::/7 — unique local addresses (RFC 4193).
            return (bytes[0].toInt() and 0xFE) == 0xFC
        }
        if (address is Inet4Address) {
            // 100.64.0.0/10 — shared address space (RFC 6598).
            val first = bytes[0].toInt() and 0xFF
            val second = bytes[1].toInt() and 0xFF
            return first == 100 && second in 64..127
        }
        return false
    }

    private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
}
