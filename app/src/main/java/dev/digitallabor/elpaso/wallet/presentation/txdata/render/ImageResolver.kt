package dev.digitallabor.elpaso.wallet.presentation.txdata.render

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import okhttp3.CookieJar
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * PaSO Core §7.4.2 step 3: resolves the external resources an entry references and
 * verifies their integrity, after step 2 has already ruled the entry structurally
 * compatible.
 *
 * PaSO View §3 states the whole contract in one paragraph:
 *
 * > The Wallet **SHALL** resolve the URL and verify the content against the `#integrity`
 * > value. When resolving, the Wallet **SHALL** enforce a fetch timeout, **SHALL** follow
 * > at most 3 redirects, and **SHALL NOT** transmit cookies, credentials, or
 * > Wallet-identifying headers. ... If verification fails, the `transaction_data` entry is
 * > not compatible.
 *
 * Every one of those is a refusal, never a degraded render, so nothing here throws for an
 * ordinary failure: a 404, a reset connection, a hash mismatch and an oversized body are
 * all [Outcome.Failed] carrying the code the consent screen logs.
 *
 * **The fetch happens because a verifier asked for it**, which is what makes the client
 * configuration part of the security surface rather than a detail — see
 * [imageFetchClient]. View §5 records the correlation channel this opens: resolving a
 * remote image reveals the transaction's timing and the wallet's network address to a
 * host the verifier named.
 */
class ImageResolver(
    private val client: HttpClient,
    private val maxEncodedBytes: Long = RenderLimits.IMAGE_MAX_ENCODED_BYTES,
    private val maxDimensionPx: Int = RenderLimits.IMAGE_MAX_DIMENSION_PX,
    private val maxRedirects: Int = RenderLimits.IMAGE_MAX_REDIRECTS,
) {
    /**
     * Verified bytes, or the reason the entry carrying this image is not compatible.
     *
     * Deliberately not `Result<ResolvedImage>`: the caller has to turn a failure into a
     * *specific* [IncompatibilityReason.Code], and with `Result` it would recover that
     * distinction by inspecting exception types — re-deriving downstream what this class
     * already knew.
     */
    sealed interface Outcome {
        data class Resolved(
            val image: ResolvedImage,
        ) : Outcome

        data class Failed(
            val code: IncompatibilityReason.Code,
            val detail: String,
        ) : Outcome
    }

    /** One hop's result, before integrity and dimensions are considered. */
    private sealed interface Step {
        data class Body(
            val bytes: ByteArray,
            val mediaType: String,
        ) : Step

        data class Redirect(
            val location: String,
        ) : Step

        data class Failed(
            val code: IncompatibilityReason.Code,
            val detail: String,
        ) : Step
    }

    suspend fun resolve(source: ImageSource.Remote): Outcome {
        // Parsed before anything goes on the wire. A hash that cannot be parsed can never
        // be satisfied, so fetching would leak the transaction's timing to the image host
        // (View §5) in exchange for an outcome already determined.
        val hash =
            Sri.parse(source.integrity)
                ?: return Outcome.Failed(
                    IncompatibilityReason.Code.IMAGE_INTEGRITY_MISSING,
                    "integrity '${source.integrity}' is not a usable SRI hash",
                )

        var current = source.url
        var hops = 0
        while (true) {
            // Re-checked every hop, not just on the original URL: §3 requires `https`, and
            // a 302 to `http://` would otherwise downgrade the transport mid-chain.
            if (!isHttps(current)) {
                return Outcome.Failed(
                    IncompatibilityReason.Code.IMAGE_INVALID_SOURCE,
                    "image URL must use the https scheme",
                )
            }

            when (val step = fetchOnce(current)) {
                is Step.Failed -> {
                    return Outcome.Failed(step.code, step.detail)
                }

                is Step.Redirect -> {
                    if (hops >= maxRedirects) {
                        return Outcome.Failed(
                            IncompatibilityReason.Code.IMAGE_INVALID_SOURCE,
                            "more than $maxRedirects redirects",
                        )
                    }
                    hops++
                    current =
                        absolutise(current, step.location)
                            ?: return Outcome.Failed(
                                IncompatibilityReason.Code.IMAGE_INVALID_SOURCE,
                                "redirect Location '${step.location}' is not a usable URL",
                            )
                }

                is Step.Body -> {
                    return verify(step, hash)
                }
            }
        }
    }

    /**
     * Integrity first, then dimensions.
     *
     * The order is the point: until the hash matches, the bytes are whatever the host
     * chose to send, and measuring them would be describing an unauthenticated object.
     */
    private fun verify(
        body: Step.Body,
        hash: Sri.Hash,
    ): Outcome {
        if (!Sri.verify(body.bytes, hash)) {
            return Outcome.Failed(
                IncompatibilityReason.Code.IMAGE_INTEGRITY_FAILED,
                "fetched content does not match its #integrity hash",
            )
        }
        val size =
            ImageDimensions.read(body.bytes)
                ?: return Outcome.Failed(
                    IncompatibilityReason.Code.IMAGE_INVALID_SOURCE,
                    "could not determine the dimensions of the fetched content",
                )
        if (size.width > maxDimensionPx || size.height > maxDimensionPx) {
            return Outcome.Failed(
                IncompatibilityReason.Code.IMAGE_DIMENSIONS,
                "image is ${size.width}x${size.height}, over the ${maxDimensionPx}px cap",
            )
        }
        return Outcome.Resolved(ResolvedImage(body.bytes, body.mediaType))
    }

    /**
     * A single request, reading the body under a hard byte cap.
     *
     * The cap is applied **while streaming**, and that is not an optimisation. Buffering
     * first and measuring afterwards means a host the verifier named decides how much
     * memory the wallet allocates — a `Content-Length` of 4 GiB, or no `Content-Length`
     * and an endless body, is then an out-of-memory kill rather than a refused entry. The
     * declared length is checked too, but only as a cheap early exit: it is a claim by the
     * same host, so it can be a lie in either direction and the streaming cap is what
     * actually holds.
     */
    private suspend fun fetchOnce(url: String): Step {
        // A verdict reached inside `execute` has to survive the teardown that follows it.
        // Abandoning an oversized body cancels the response, and that cancellation surfaces
        // as an exception thrown *out* of `execute` — so a blanket runCatching would report
        // "too large" as a transport failure. Wrong code, and a much worse log line: the
        // interesting fact is that a host tried to send more than the cap allows.
        var decided: Step? = null
        return runCatching {
            client
                .prepareGet(url) {
                    header(HttpHeaders.Accept, ACCEPT)
                }.execute { response -> readBody(response).also { decided = it } }
        }.getOrElse { error ->
            decided
                ?: Step.Failed(
                    // A transport failure is an ordinary outcome for a verifier-supplied URL,
                    // not an exception for the consent screen to handle.
                    IncompatibilityReason.Code.IMAGE_INVALID_SOURCE,
                    "fetch failed: ${error.javaClass.simpleName}",
                )
        }
    }

    private suspend fun readBody(response: HttpResponse): Step {
        if (response.status.value in 300..399) {
            val location =
                response.headers[HttpHeaders.Location]
                    ?: return Step.Failed(
                        IncompatibilityReason.Code.IMAGE_INVALID_SOURCE,
                        "redirect without a Location header",
                    )
            return Step.Redirect(location)
        }
        if (!response.status.isSuccess()) {
            return Step.Failed(
                IncompatibilityReason.Code.IMAGE_INVALID_SOURCE,
                "HTTP ${response.status.value} fetching the image",
            )
        }

        val declared = response.contentLength()
        if (declared != null && declared > maxEncodedBytes) {
            return Step.Failed(
                IncompatibilityReason.Code.IMAGE_TOO_LARGE,
                "declared $declared bytes, over the $maxEncodedBytes cap",
            )
        }

        val channel = response.bodyAsChannel()
        val sink = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK_BYTES)
        while (true) {
            val read = channel.readAvailable(chunk, 0, chunk.size)
            if (read < 0) break
            if (read == 0) continue
            if (sink.size().toLong() + read > maxEncodedBytes) {
                // Cancelling here is what bounds the allocation, and it releases the
                // producer immediately rather than leaving it blocked on a full channel.
                channel.cancel()
                return Step.Failed(
                    IncompatibilityReason.Code.IMAGE_TOO_LARGE,
                    "body exceeds the $maxEncodedBytes byte cap",
                )
            }
            sink.write(chunk, 0, read)
        }

        val mediaType =
            response.contentType()?.let { "${it.contentType}/${it.contentSubtype}" }
                ?: DEFAULT_MEDIA_TYPE
        return Step.Body(sink.toByteArray(), mediaType)
    }

    private fun isHttps(url: String): Boolean = runCatching { URI(url).scheme?.lowercase() }.getOrNull() == HTTPS

    /** Resolves a possibly-relative `Location` against the URL that produced it. */
    private fun absolutise(
        base: String,
        location: String,
    ): String? = runCatching { URI(base).resolve(location).toString() }.getOrNull()

    companion object {
        private const val HTTPS = "https"
        private const val READ_CHUNK_BYTES = 8 * 1024
        private const val DEFAULT_MEDIA_TYPE = "application/octet-stream"

        /**
         * §3: "the Wallet SHALL send an `Accept` header listing the image media types it
         * supports" — and §3 requires at least PNG, JPEG and SVG.
         */
        const val ACCEPT = "image/png, image/jpeg, image/svg+xml"

        /**
         * A generic desktop token rather than anything naming this wallet. §3 forbids
         * transmitting "Wallet-identifying headers", and the default any HTTP stack
         * supplies — `okhttp/4.x`, `Ktor client` — is a fingerprint contributed for no
         * benefit to a host that has no need to know what fetched the image.
         */
        private const val NEUTRAL_USER_AGENT = "Mozilla/5.0"

        /**
         * The client [ImageResolver] must be given, and the reason this factory lives
         * beside the resolver rather than in the DI module: every line of it is load-bearing
         * for View §3, and a client assembled elsewhere would silently drop one.
         *
         * It is emphatically **not** `HttpClientFactory.create()`. That client exists for
         * issuance and presentation and carries three things that must never reach a
         * verifier-named image host:
         *
         *  - a request/response logger that writes full URLs, headers and bodies to logcat;
         *  - a `/token` interceptor that rewrites and records error payloads;
         *  - `followRedirects(true)` and a shared 20 s timeout.
         *
         * Redirects are disabled at both layers so [resolve] can count the hops itself;
         * §3 caps them at 3, and an engine following them silently would enforce its own
         * default instead — 20, in OkHttp's case.
         */
        fun imageFetchClient(): HttpClient =
            HttpClient(OkHttp) {
                followRedirects = false
                // A non-2xx is a verdict to report, not an exception to throw.
                expectSuccess = false
                install(UserAgent) { agent = NEUTRAL_USER_AGENT }
                install(HttpTimeout) {
                    requestTimeoutMillis = 10_000
                    connectTimeoutMillis = 5_000
                    socketTimeoutMillis = 5_000
                }
                engine {
                    config {
                        followRedirects(false)
                        followSslRedirects(false)
                        cookieJar(CookieJar.NO_COOKIES)
                        connectTimeout(5, TimeUnit.SECONDS)
                        readTimeout(5, TimeUnit.SECONDS)
                        writeTimeout(5, TimeUnit.SECONDS)
                    }
                }
            }
    }
}
