package dev.digitallabor.elpaso.wallet.vct

import android.util.Log
import dev.digitallabor.elpaso.wallet.data.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.isSuccess

/**
 * Fetches SD-JWT VC Type Metadata by performing a direct GET on the credential's `vct` URL.
 * Returns [Result.failure] on any network or parse error so callers can fall back gracefully.
 */
class VctMetadataClient(private val httpClient: HttpClient) {

    suspend fun fetch(vctUrl: String): Result<VctMetadata> = runCatching {
        val response = httpClient.get(vctUrl) {
            accept(ContentType.Application.Json)
        }
        if (!response.status.isSuccess()) {
            error("VCT metadata fetch returned ${response.status} for $vctUrl")
        }
        val body = response.bodyAsText()
        HttpClientFactory.json.decodeFromString(VctMetadata.serializer(), body)
    }.onFailure { Log.w(LOG_TAG, "VCT metadata fetch failed for $vctUrl", it) }

    companion object {
        private const val LOG_TAG = "VctMetadataClient"
    }
}
