package dev.digitallabor.elpaso.wallet.data.network

import android.util.Log
import dev.digitallabor.elpaso.wallet.presentation.paso.RequestIntegrityRecorder
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

object HttpClientFactory {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    /**
     * Most recently observed non-2xx response body — exposed so the issuance flow can
     * surface the real server message when the EUDI library's strict OAuth-error
     * deserializer (`TokenResponseTO.Failure` requires `error`) fails with a
     * MissingFieldException that hides the actual payload.
     */
    data class LastErrorBody(val method: String, val url: String, val status: Int, val body: String)

    private val lastErrorBody = AtomicReference<LastErrorBody?>(null)

    fun consumeLastErrorBody(): LastErrorBody? = lastErrorBody.getAndSet(null)

    /**
     * If [text] is JSON but doesn't have a top-level `error` field, try to dig one out of
     * common wrapper shapes (e.g. NestJS exception filters that produce
     * `{"statusCode":400,"message":{"error":"..."}}` or `{"message":"..."}`). Returns a
     * RFC 6749 §5.2-shaped string `{"error":"...","error_description":"..."}` on success,
     * or null if [text] already conforms or we can't make sense of it (in which case the
     * caller leaves the original body alone and the EUDI deserializer fails as it would
     * naturally).
     */
    private fun rewriteAsOauthError(text: String): String? = runCatching {
        val root = json.parseToJsonElement(text) as? JsonObject ?: return@runCatching null
        if (root["error"]?.let { it is JsonPrimitive && it.isString } == true) return@runCatching null
        val (error, description) = extractOauthErrorFromWrapper(root) ?: return@runCatching null
        buildJsonObject {
            put("error", JsonPrimitive(error))
            if (description != null) put("error_description", JsonPrimitive(description))
        }.toString()
    }.getOrNull()

    private fun extractOauthErrorFromWrapper(root: JsonObject): Pair<String, String?>? {
        val message = root["message"]
        if (message is JsonObject) {
            val inner = message["error"]?.let { if (it is JsonPrimitive && it.isString) it.content else null }
            val innerDesc = message["error_description"]?.let { if (it is JsonPrimitive && it.isString) it.content else null }
            if (inner != null) return inner to innerDesc
        }
        if (message is JsonPrimitive && message.isString) {
            return "invalid_request" to message.content
        }
        val topDesc = root["error_description"]?.let { if (it is JsonPrimitive && it.isString) it.content else null }
        if (topDesc != null) return "invalid_request" to topDesc
        return null
    }

    private val exchangeCounter = AtomicLong(0)

    private fun isTextLikeContentType(type: String?, subtype: String?): Boolean {
        if (type == null || subtype == null) return true
        if (type == "text") return true
        if (type == "application") {
            return subtype == "json" || subtype == "xml" || subtype == "javascript" ||
                subtype == "x-www-form-urlencoded" || subtype == "jose" || subtype == "jwt" ||
                subtype == "oauth-authz-req+jwt" || subtype.endsWith("+json") ||
                subtype.endsWith("+jwt") || subtype.endsWith("+xml") || subtype.endsWith("+jose")
        }
        return false
    }

    fun create(): HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 8_000
            socketTimeoutMillis = 10_000
        }
        engine {
            config {
                connectTimeout(8, TimeUnit.SECONDS)
                readTimeout(10, TimeUnit.SECONDS)
                writeTimeout(10, TimeUnit.SECONDS)
                followRedirects(true)
            }
            // OkHttp-level interceptor for `/token` error responses. Added FIRST so it's the
            // OUTERMOST on responses (runs AFTER the body logger below in response order),
            // ensuring the logger sees the original server body before this interceptor's
            // RFC 6749 §5.2 rewrite. Purpose:
            //   1) capture the raw body into `lastErrorBody` before any deserializer can
            //      throw;
            //   2) if the body is a non-OAuth envelope (NestJS-style `{message:{error:...}}`),
            //      rewrite it to `{"error":"...", ...}` so the EUDI library deserializes it
            //      cleanly and throws a real AccessTokenRequestFailed instead of a
            //      MissingFieldException.
            addInterceptor { chain ->
                val request = chain.request()
                val response = chain.proceed(request)
                if (!request.url.pathSegments.lastOrNull().equals("token", ignoreCase = false)) {
                    return@addInterceptor response
                }
                if (response.code !in 400..599) return@addInterceptor response
                val body = response.body ?: return@addInterceptor response
                val contentType = body.contentType()
                val bytes = body.bytes()
                val text = bytes.toString(Charsets.UTF_8)
                lastErrorBody.set(
                    LastErrorBody(
                        method = request.method,
                        url = request.url.toString(),
                        status = response.code,
                        body = text,
                    ),
                )
                val rewritten = rewriteAsOauthError(text) ?: text
                // `body.bytes()` already consumed and decompressed the response, so the
                // original Content-Length / Content-Encoding headers no longer describe what
                // we're handing back. `toResponseBody` will compute the correct length.
                response.newBuilder()
                    .body(rewritten.toResponseBody(contentType ?: "application/json".toMediaTypeOrNull()))
                    .removeHeader("Content-Length")
                    .removeHeader("Content-Encoding")
                    .build()
            }
            // Capture JAR Authorization Request JWT bodies into RequestIntegrityRecorder
            // so the PaSO SCA Response Claim `request_integrity` (W3C SRI over the
            // compact-serialised JWT as received) can be computed post-resolution.
            // Triggers on `application/oauth-authz-req+jwt` responses; harmless for other
            // traffic. Wired here rather than as a Ktor plugin because we need byte-exact
            // access to the response body before Ktor's content negotiation touches it.
            addInterceptor { chain ->
                val request = chain.request()
                val response = chain.proceed(request)
                val ct = response.body?.contentType()
                val isJarJwt = ct?.type == "application" &&
                    (ct.subtype == "oauth-authz-req+jwt" || ct.subtype.endsWith("+jwt"))
                if (!isJarJwt) return@addInterceptor response
                val peeked = runCatching {
                    response.peekBody(MAX_BODY_LOG_BYTES).bytes()
                }.getOrNull() ?: return@addInterceptor response
                RequestIntegrityRecorder.record(request.url.toString(), peeked)
                response
            }
            // Comprehensive request/response logger. Runs synchronously at the OkHttp
            // application-interceptor layer, where gzip is already decompressed but Ktor
            // hasn't deserialized yet. Logs every interaction (issuance, presentation,
            // well-known, trust-list fetch) with method, URL, headers, and bodies for both
            // directions. Bodies are read via okio.Buffer (request) and Response.peekBody
            // (response) so they remain available to downstream interceptors and to Ktor.
            // Added LAST so it's the INNERMOST on responses, i.e. processes the response
            // first and logs the original server body before any sibling interceptor (e.g.
            // the /token error rewriter above) can mutate it.
            addInterceptor { chain ->
                val request = chain.request()
                val id = exchangeCounter.incrementAndGet()
                val reqBody = request.body
                val reqBodyText = reqBody?.let { rb ->
                    runCatching {
                        val ct = rb.contentType()
                        val buf = Buffer()
                        rb.writeTo(buf)
                        if (isTextLikeContentType(ct?.type, ct?.subtype) && buf.size <= MAX_BODY_LOG_BYTES) {
                            buf.readString(ct?.charset() ?: Charsets.UTF_8)
                        } else {
                            "<${buf.size}B ${ct ?: "binary"}>"
                        }
                    }.getOrElse { "<unreadable: ${it.javaClass.simpleName}>" }
                }
                Log.w("HttpDebug", "[#$id] → ${request.method} ${redactSecrets(request.url.toString())}")
                request.headers.forEach { (k, v) -> Log.w("HttpDebug", "[#$id]   $k: ${redactHeader(k, v)}") }
                if (reqBodyText != null) Log.w("HttpDebug", "[#$id]   body: $reqBodyText")

                val response = chain.proceed(request)

                val respCt = response.body?.contentType()
                val respText = runCatching {
                    if (isTextLikeContentType(respCt?.type, respCt?.subtype)) {
                        response.peekBody(MAX_BODY_LOG_BYTES).string()
                    } else {
                        "<${response.body?.contentLength() ?: -1}B ${respCt ?: "binary"}>"
                    }
                }.getOrElse { "<unreadable: ${it.javaClass.simpleName}>" }
                Log.w("HttpDebug", "[#$id] ← ${response.code} ${request.method} ${redactSecrets(request.url.toString())}")
                response.headers.forEach { (k, v) -> Log.w("HttpDebug", "[#$id]   $k: ${redactHeader(k, v)}") }
                Log.w("HttpDebug", "[#$id]   body: $respText")

                if (response.code in 400..599) {
                    lastErrorBody.compareAndSet(
                        null,
                        LastErrorBody(
                            method = request.method,
                            url = request.url.toString(),
                            status = response.code,
                            body = respText,
                        ),
                    )
                }
                response
            }
        }
    }

    private const val MAX_BODY_LOG_BYTES = 256L * 1024L

    // URL query parameters whose values are secrets — masked before logging. Add
    // entries here when new secret-bearing endpoints land. Match is case-insensitive.
    private val SECRET_QUERY_PARAMS = setOf("key", "access_token", "token", "api_key", "apikey")

    /** Replaces secret-bearing query params and Authorization-style headers with `***`. */
    internal fun redactSecrets(url: String): String {
        val q = url.indexOf('?').takeIf { it >= 0 } ?: return url
        val base = url.substring(0, q + 1)
        val params = url.substring(q + 1).split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) return@joinToString pair
            val name = pair.substring(0, eq)
            if (SECRET_QUERY_PARAMS.any { it.equals(name, ignoreCase = true) }) "$name=***" else pair
        }
        return base + params
    }

    internal fun redactHeader(name: String, value: String): String = when {
        name.equals("Authorization", ignoreCase = true) -> {
            // Keep the scheme ("Bearer ", "Basic ", …) so the log still tells you
            // what kind of auth was on the wire.
            val space = value.indexOf(' ')
            if (space > 0) value.substring(0, space + 1) + "***" else "***"
        }
        name.equals("Cookie", ignoreCase = true) ||
            name.equals("Set-Cookie", ignoreCase = true) -> "***"
        else -> value
    }
}
