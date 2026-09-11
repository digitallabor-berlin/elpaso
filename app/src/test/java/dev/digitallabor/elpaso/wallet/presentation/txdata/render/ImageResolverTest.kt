package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/**
 * PaSO View §3, the remote half of `image`:
 *
 * > If the URL is not a Data URL, it **MUST** use the `https` scheme and the `payload`
 * > **MUST** contain a sibling claim at the same path suffixed with `#integrity` ... The
 * > Wallet **SHALL** resolve the URL and verify the content against the `#integrity`
 * > value. When resolving, the Wallet **SHALL** enforce a fetch timeout, **SHALL** follow
 * > at most 3 redirects, and **SHALL NOT** transmit cookies, credentials, or
 * > Wallet-identifying headers. ... If verification fails, the `transaction_data` entry is
 * > not compatible.
 *
 * Ktor's [MockEngine] rather than OkHttp's MockWebServer: this project's HTTP stack is
 * Ktor (`HttpClient(OkHttp)`), `ktor-client-mock` is already a `testImplementation`, and
 * there is no `mockwebserver` artifact to import.
 */
class ImageResolverTest {
    private val url = "https://images.example/logo.png"

    private fun sriOf(bytes: ByteArray): String =
        "sha256-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun be32(value: Int) =
        byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )

    /** A byte-valid PNG header of the given size; enough for the dimension reader. */
    private fun png(
        width: Int = 64,
        height: Int = 64,
        padTo: Int = 0,
    ): ByteArray {
        val head =
            ByteArrayOutputStream()
                .apply {
                    write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
                    write(be32(13))
                    write("IHDR".toByteArray())
                    write(be32(width))
                    write(be32(height))
                    write(byteArrayOf(8, 6, 0, 0, 0))
                }.toByteArray()
        return if (padTo > head.size) head + ByteArray(padTo - head.size) else head
    }

    private fun resolver(
        maxEncodedBytes: Long = RenderLimits.IMAGE_MAX_ENCODED_BYTES,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): ImageResolver =
        ImageResolver(
            client =
                HttpClient(MockEngine(handler)) {
                    followRedirects = false
                    install(HttpTimeout)
                },
            maxEncodedBytes = maxEncodedBytes,
        )

    private fun codeOf(outcome: ImageResolver.Outcome): IncompatibilityReason.Code {
        assertTrue("expected a failure, got $outcome", outcome is ImageResolver.Outcome.Failed)
        return (outcome as ImageResolver.Outcome.Failed).code
    }

    private fun imageOf(outcome: ImageResolver.Outcome): ResolvedImage {
        assertTrue("expected success, got $outcome", outcome is ImageResolver.Outcome.Resolved)
        return (outcome as ImageResolver.Outcome.Resolved).image
    }

    // --- Happy path ---

    @Test
    fun verifiedImageIsResolved() =
        runTest {
            val bytes = png()
            val outcome =
                resolver {
                    respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
                }.resolve(ImageSource.Remote(url, sriOf(bytes)))
            val image = imageOf(outcome)
            assertTrue(bytes.contentEquals(image.bytes))
            assertEquals("image/png", image.mediaType)
        }

    // --- Integrity (§3: "If verification fails, the entry is not compatible") ---

    @Test
    fun integrityMismatchIsIncompatible() =
        runTest {
            val outcome =
                resolver {
                    respond(png(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
                }.resolve(ImageSource.Remote(url, sriOf("different bytes".toByteArray())))
            assertEquals(IncompatibilityReason.Code.IMAGE_INTEGRITY_FAILED, codeOf(outcome))
        }

    /**
     * An unparseable hash can never be satisfied, so the entry is refused without a fetch.
     * Reported as MISSING rather than FAILED: nothing was verified and found wrong, the
     * metadata simply never supplied a usable hash.
     */
    @Test
    fun unparseableIntegrityIsRefusedWithoutFetching() =
        runTest {
            var requested = false
            val outcome =
                resolver {
                    requested = true
                    respond(png(), HttpStatusCode.OK)
                }.resolve(ImageSource.Remote(url, "md5-abc"))
            assertEquals(IncompatibilityReason.Code.IMAGE_INTEGRITY_MISSING, codeOf(outcome))
            assertTrue("must not fetch when the hash is unusable", !requested)
        }

    // --- Size cap (§3: "MUST NOT exceed 512 KiB in encoded size") ---

    @Test
    fun declaredOversizeIsRejected() =
        runTest {
            val cap = 1024L
            val outcome =
                resolver(maxEncodedBytes = cap) {
                    respond(
                        ByteArray(4096),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentLength, "4096"),
                    )
                }.resolve(ImageSource.Remote(url, sriOf(ByteArray(4096))))
            assertEquals(IncompatibilityReason.Code.IMAGE_TOO_LARGE, codeOf(outcome))
        }

    /**
     * The cap must be enforced against the bytes actually read, not the declared length —
     * the host is chosen by the verifier and a truthful `Content-Length` cannot be assumed.
     */
    @Test
    fun oversizeIsRejectedWhenContentLengthLies() =
        runTest {
            val cap = 1024L
            val body = ByteArray(8192)
            val outcome =
                resolver(maxEncodedBytes = cap) {
                    respond(
                        ByteReadChannel(body),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "image/png"),
                    )
                }.resolve(ImageSource.Remote(url, sriOf(body)))
            assertEquals(IncompatibilityReason.Code.IMAGE_TOO_LARGE, codeOf(outcome))
        }

    /**
     * The decisive one: an endless body, which **only terminates if the read is bounded**.
     * An implementation that drains the body and measures afterwards cannot ever reach a
     * verdict here; it exhausts the timeout, or the heap.
     *
     * Termination is the whole assertion, and deliberately so. Counting the bytes the
     * producer managed to write does not measure this: [MockEngine] pumps a supplied
     * channel into its own response channel, so that number reflects engine buffering
     * between two channels rather than anything the resolver read — an earlier version of
     * this test asserted on it and saw 1.4 MB against a 64 KiB cap from a resolver that
     * was, in fact, correctly abandoning the stream after 64 KiB.
     */
    @Test
    fun endlessBodyIsAbandonedMidStream() =
        runBlocking {
            val cap = 64L * 1024
            val chunk = ByteArray(8 * 1024)
            val channel = ByteChannel(autoFlush = true)
            val written = AtomicLong(0)
            // A real dispatcher rather than runTest's scheduler: the whole point is genuine
            // concurrency between a producer that never stops and a reader that must stop
            // regardless.
            val producer =
                launch(Dispatchers.Default) {
                    runCatching {
                        while (true) {
                            channel.writeFully(chunk)
                            written.addAndGet(chunk.size.toLong())
                        }
                    }
                }
            // Bounds the damage if the read is ever made unbounded again: a buffering
            // implementation fails here in 30s instead of running until the JVM dies.
            val outcome =
                withTimeout(30_000) {
                    resolver(maxEncodedBytes = cap) {
                        respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
                    }.resolve(ImageSource.Remote(url, sriOf(chunk)))
                }
            channel.cancel()
            producer.cancelAndJoin()

            assertEquals(IncompatibilityReason.Code.IMAGE_TOO_LARGE, codeOf(outcome))
            // Not an assertion about the cap — just evidence the producer really was
            // endless, so that reaching a verdict at all means the read stopped early.
            assertTrue("the producer never ran", written.get() > cap)
        }

    // --- Redirects (§3: "SHALL follow at most 3 redirects") ---

    @Test
    fun followsUpToThreeRedirects() =
        runTest {
            val bytes = png()
            val outcome =
                resolver { request ->
                    when (request.url.encodedPath) {
                        "/0" -> respond(ByteArray(0), HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://images.example/1"))
                        "/1" -> respond(ByteArray(0), HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://images.example/2"))
                        "/2" -> respond(ByteArray(0), HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://images.example/3"))
                        else -> respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
                    }
                }.resolve(ImageSource.Remote("https://images.example/0", sriOf(bytes)))
            assertTrue(bytes.contentEquals(imageOf(outcome).bytes))
        }

    @Test
    fun fourthRedirectIsRefused() =
        runTest {
            val outcome =
                resolver { request ->
                    val next =
                        request.url.encodedPath
                            .trimStart('/')
                            .toInt() + 1
                    respond(ByteArray(0), HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://images.example/$next"))
                }.resolve(ImageSource.Remote("https://images.example/0", sriOf(png())))
            assertEquals(IncompatibilityReason.Code.IMAGE_INVALID_SOURCE, codeOf(outcome))
        }

    /** §3 makes `https` mandatory; a redirect must not be able to downgrade the hop. */
    @Test
    fun redirectToNonHttpsIsRefused() =
        runTest {
            val outcome =
                resolver {
                    respond(ByteArray(0), HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://images.example/plain"))
                }.resolve(ImageSource.Remote(url, sriOf(png())))
            assertEquals(IncompatibilityReason.Code.IMAGE_INVALID_SOURCE, codeOf(outcome))
        }

    @Test
    fun redirectWithoutLocationIsRefused() =
        runTest {
            val outcome =
                resolver {
                    respond(ByteArray(0), HttpStatusCode.Found)
                }.resolve(ImageSource.Remote(url, sriOf(png())))
            assertEquals(IncompatibilityReason.Code.IMAGE_INVALID_SOURCE, codeOf(outcome))
        }

    // --- Headers (§3: Accept listed; no cookies, credentials, or identifying headers) ---

    @Test
    fun sendsAcceptAndNoWalletIdentifyingHeaders() =
        runTest {
            var seen: io.ktor.http.Headers? = null
            val bytes = png()
            resolver { request ->
                seen = request.headers
                respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
            }.resolve(ImageSource.Remote(url, sriOf(bytes)))

            val headers = requireNotNull(seen)
            val accept = requireNotNull(headers[HttpHeaders.Accept]) { "Accept is mandated by View §3" }
            assertTrue("image/png", accept.contains("image/png"))
            assertTrue("image/jpeg", accept.contains("image/jpeg"))
            assertTrue("image/svg+xml", accept.contains("image/svg+xml"))
            assertNull(headers[HttpHeaders.Cookie])
            assertNull(headers[HttpHeaders.Authorization])
        }

    // --- Dimensions (§3: "MUST NOT exceed 2048 pixels in either direction") ---

    @Test
    fun oversizeDimensionsAreRefused() =
        runTest {
            val bytes = png(width = 4096, height = 10)
            val outcome =
                resolver {
                    respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
                }.resolve(ImageSource.Remote(url, sriOf(bytes)))
            assertEquals(IncompatibilityReason.Code.IMAGE_DIMENSIONS, codeOf(outcome))
        }

    @Test
    fun exactlyAtTheDimensionCapIsAccepted() =
        runTest {
            val bytes = png(width = RenderLimits.IMAGE_MAX_DIMENSION_PX, height = RenderLimits.IMAGE_MAX_DIMENSION_PX)
            val outcome =
                resolver {
                    respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
                }.resolve(ImageSource.Remote(url, sriOf(bytes)))
            assertTrue(bytes.contentEquals(imageOf(outcome).bytes))
        }

    /**
     * Bytes whose format the wallet cannot read are refused rather than displayed: the
     * 2048 px bound is a MUST, and content whose dimensions cannot be established has not
     * been shown to satisfy it.
     */
    @Test
    fun undeterminableFormatIsRefused() =
        runTest {
            val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
            val outcome =
                resolver {
                    respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
                }.resolve(ImageSource.Remote(url, sriOf(bytes)))
            assertEquals(IncompatibilityReason.Code.IMAGE_INVALID_SOURCE, codeOf(outcome))
        }

    // --- Transport failures ---

    @Test
    fun nonSuccessStatusIsRefused() =
        runTest {
            val outcome =
                resolver {
                    respond(ByteArray(0), HttpStatusCode.NotFound)
                }.resolve(ImageSource.Remote(url, sriOf(png())))
            assertEquals(IncompatibilityReason.Code.IMAGE_INVALID_SOURCE, codeOf(outcome))
        }

    /** A network error is an ordinary outcome here, not an exception for a caller to catch. */
    @Test
    fun transportFailureIsRefusedNotThrown() =
        runTest {
            val outcome =
                resolver {
                    throw java.io.IOException("connection reset")
                }.resolve(ImageSource.Remote(url, sriOf(png())))
            assertEquals(IncompatibilityReason.Code.IMAGE_INVALID_SOURCE, codeOf(outcome))
        }

    @Test
    fun nonHttpsSourceIsRefused() =
        runTest {
            val outcome =
                resolver {
                    respond(png(), HttpStatusCode.OK)
                }.resolve(ImageSource.Remote("http://images.example/logo.png", sriOf(png())))
            assertEquals(IncompatibilityReason.Code.IMAGE_INVALID_SOURCE, codeOf(outcome))
        }
}
