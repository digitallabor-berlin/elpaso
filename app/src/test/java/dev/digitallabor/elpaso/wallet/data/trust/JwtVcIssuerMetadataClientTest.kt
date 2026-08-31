package dev.digitallabor.elpaso.wallet.data.trust

import dev.digitallabor.elpaso.wallet.testing.TestPki
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * draft-ietf-oauth-sd-jwt-vc-11 §5.2 (`issuer` REQUIRED; exactly one of `jwks` /
 * `jwks_uri`), §5.3 (`issuer` MUST equal `iss`, else "the data contained in the
 * response MUST NOT be used") and §10.1 (time- and size-bound request).
 */
class JwtVcIssuerMetadataClientTest {
    private val iss = "https://issuer.example"
    private val wellKnown = "https://issuer.example/.well-known/jwt-vc-issuer"
    private val now = TestPki.NOW
    private val publicResolver = HostResolver { listOf(InetAddress.getByName("93.184.216.34")) }

    private val signingKey = TestPki.jwk(TestPki.ca("CN=Key Set Subject"), kid = "k1")
    private val jwks = """{"keys":[${signingKey.toJSONString()}]}"""

    /** Serves a fixed body per URL; any unexpected URL fails the test loudly. */
    private fun client(vararg routes: Pair<String, String>): HttpClient {
        val table = routes.toMap()
        val engine =
            MockEngine { request ->
                val body = table[request.url.toString()] ?: error("unexpected request to ${request.url}")
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        return HttpClient(engine) { install(HttpTimeout) }
    }

    private fun subject(client: HttpClient) = JwtVcIssuerMetadataClient(client, publicResolver)

    private fun failureMessage(result: Result<*>): String {
        assertTrue("expected failure, got $result", result.isFailure)
        return result.exceptionOrNull()!!.message ?: ""
    }

    @Test
    fun `inline jwks is accepted`() =
        runTest {
            val document = """{"issuer":"$iss","jwks":$jwks}"""
            val result = subject(client(wellKnown to document)).fetch(iss, now)
            assertTrue("${result.exceptionOrNull()?.message}", result.isSuccess)
            val keySet = result.getOrThrow()
            assertEquals(iss, keySet.issuer)
            assertEquals(1, keySet.keys.size)
            assertEquals("k1", keySet.keys.single().keyID)
            assertEquals(wellKnown, keySet.sourceUrl)
            assertEquals(now, keySet.fetchedAt)
        }

    @Test
    fun `jwks_uri is followed and becomes the sourceUrl`() =
        runTest {
            val keysUrl = "https://issuer.example/keys.json"
            val document = """{"issuer":"$iss","jwks_uri":"$keysUrl"}"""
            val result = subject(client(wellKnown to document, keysUrl to jwks)).fetch(iss, now)
            assertTrue("${result.exceptionOrNull()?.message}", result.isSuccess)
            assertEquals(keysUrl, result.getOrThrow().sourceUrl)
        }

    @Test
    fun `issuer mismatch is rejected per §5_3`() =
        runTest {
            val document = """{"issuer":"https://other.example","jwks":$jwks}"""
            val message = failureMessage(subject(client(wellKnown to document)).fetch(iss, now))
            assertTrue(message, message.contains("issuer"))
        }

    @Test
    fun `both jwks and jwks_uri is rejected per §5_2`() =
        runTest {
            val document = """{"issuer":"$iss","jwks":$jwks,"jwks_uri":"https://issuer.example/keys.json"}"""
            val message = failureMessage(subject(client(wellKnown to document)).fetch(iss, now))
            assertTrue(message, message.contains("not both"))
        }

    @Test
    fun `neither jwks nor jwks_uri is rejected per §5_2`() =
        runTest {
            val document = """{"issuer":"$iss"}"""
            val message = failureMessage(subject(client(wellKnown to document)).fetch(iss, now))
            assertTrue(message, message.contains("either"))
        }

    @Test
    fun `an empty key set is rejected`() =
        runTest {
            val document = """{"issuer":"$iss","jwks":{"keys":[]}}"""
            val message = failureMessage(subject(client(wellKnown to document)).fetch(iss, now))
            assertTrue(message, message.contains("no keys"))
        }

    @Test
    fun `an oversized body is rejected rather than buffered`() =
        runTest {
            val padding = "x".repeat(JwtVcIssuerMetadataClient.MAX_BODY_BYTES + 1024)
            val document = """{"issuer":"$iss","jwks":$jwks,"padding":"$padding"}"""
            val message = failureMessage(subject(client(wellKnown to document)).fetch(iss, now))
            assertTrue(message, message.contains("exceeds"))
        }

    @Test
    fun `a jwks_uri targeting an internal address is rejected by the guard`() =
        runTest {
            val keysUrl = "https://127.0.0.1/keys.json"
            val document = """{"issuer":"$iss","jwks_uri":"$keysUrl"}"""
            // Only the well-known URL is routed; a request to the internal URL would be a bug.
            val message = failureMessage(subject(client(wellKnown to document)).fetch(iss, now))
            assertTrue(message, message.contains("internal address"))
        }

    @Test
    fun `a non-https iss never reaches the network`() =
        runTest {
            val message = failureMessage(subject(client()).fetch("http://issuer.example", now))
            assertTrue(message, message.contains("https"))
        }

    @Test
    fun `a non-200 response is rejected`() =
        runTest {
            val engine = MockEngine { respond(content = "nope", status = HttpStatusCode.NotFound) }
            val client = HttpClient(engine) { install(HttpTimeout) }
            val message = failureMessage(subject(client).fetch(iss, now))
            assertTrue(message, message.contains("404"))
        }

    @Test
    fun `a non-JSON content type is rejected per §5_2`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = """{"issuer":"$iss","jwks":$jwks}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/html"),
                    )
                }
            val client = HttpClient(engine) { install(HttpTimeout) }
            val message = failureMessage(subject(client).fetch(iss, now))
            assertTrue(message, message.contains("content type"))
        }
}
