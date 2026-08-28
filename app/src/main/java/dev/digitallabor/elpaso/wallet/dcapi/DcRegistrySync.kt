package dev.digitallabor.elpaso.wallet.dcapi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.credentials.registry.provider.RegistryManager
import dev.digitallabor.elpaso.wallet.R
import dev.digitallabor.elpaso.wallet.data.store.CredentialRepository
import dev.digitallabor.elpaso.wallet.domain.model.Credential
import dev.digitallabor.elpaso.wallet.domain.model.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pushes the wallet's credentials (SD-JWT VC and mdoc) into the Android Digital
 * Credentials registry.
 *
 * The platform's sandboxed WASM matcher consumes the registered entries to decide which
 * credential satisfies an incoming OpenID4VP request — that's why we must mirror every
 * disclosable claim here, not just the bare credential.
 *
 * We ship a matcher built in-house from CMWallet's reference C implementation (see
 * `matcher/` and `app/src/main/assets/openid4vp1_0.wasm`) rather than the binary bundled
 * with `OpenId4VpRegistry`. Building it ourselves is what lets us carry two small local
 * deltas — PaSO SCA payment rendering, and degrading a *non-payment* `transaction_data`
 * type to an ordinary entry instead of a blank payment one that the picker drops — while
 * keeping upstream's DCQL semantics, including credential sets, signed and multisigned
 * requests, and inline issuance entries.
 *
 * The credential payload itself IS the stock `OpenId4VpRegistry` blob: we build that
 * registry, take its bytes, and pair them with our own matcher. See
 * [DcRegistryBlobBuilder].
 */
class DcRegistrySync(
    private val context: Context,
    private val repository: CredentialRepository,
) {
    private val registryManager by lazy { RegistryManager.create(context) }

    // Loaded once on first registration. Kept in memory because the registry call needs
    // the bytes on every refresh — re-reading from assets each time is wasteful and we
    // can't change the wasm without an app reinstall anyway.
    private val matcherWasm: ByteArray by lazy {
        context.assets.open(MATCHER_ASSET).use { it.readBytes() }
    }

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        // 1) Eager initial push so the registry is populated before any verifier can race
        // the 250 ms debounce window. Without this, on a cold start the wallet is
        // invisible to the system DC API picker for that window.
        // 2) Long-running listener that re-registers on credential add/remove/update.
        scope.launch { runCatching { registerNow() }.onFailure { Log.w(LOG_TAG, "Initial register failed", it) } }
        scope.launch {
            repository
                .observeAll()
                .debounce(DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { credentials -> register(credentials) }
        }
    }

    /**
     * Push the current credential set to the registry immediately, bypassing the debounce
     * on the flow-based listener. Call this from the issuance flow right after a credential
     * is persisted so the new entry is matchable by the DC API as soon as issuance returns —
     * verifiers can race the user's "Done" tap if we wait for the debounce window.
     */
    suspend fun registerNow() {
        register(repository.observeAll().first())
    }

    private suspend fun register(credentials: List<Credential>) {
        val sdJwtCount = credentials.count { it.format == Format.SdJwtVc }
        val mdocCount = credentials.count { it.format == Format.MsoMdoc }
        val blob = DcRegistryBlobBuilder.build(credentials, launcherIcon(), REGISTRY_ID)
        Log.i(
            LOG_TAG,
            "Registering total=${credentials.size} sdjwt=$sdJwtCount mdoc=$mdocCount " +
                "blob_bytes=${blob.size} matcher_bytes=${matcherWasm.size}",
        )
        dumpPackageForDebug(blob)
        val request =
            CustomMatcherRegistry(
                id = REGISTRY_ID,
                credentialsJson = blob,
                matcherWasm = matcherWasm,
            )
        runCatching { registryManager.registerCredentials(request) }
            .onSuccess { Log.i(LOG_TAG, "registerCredentials succeeded (${credentials.size} entries)") }
            .onFailure { Log.w(LOG_TAG, "registerCredentials failed", it) }
    }

    /**
     * Mirror the registry blob we are about to register to internal storage so it can be
     * pulled off-device (`adb pull /data/data/<pkg>/files/dcapi_package.bin`) and checked
     * with `scripts/verify-registry-blob.py`, and chunk-log the JSON tail so it is visible
     * in logcat too.
     *
     * The blob is binary — a 4-byte little-endian offset to the JSON, then icon bytes —
     * so only the tail is text. This is the tool that explains why a credential did not
     * match; keep it working.
     */
    private fun dumpPackageForDebug(packageBytes: ByteArray) {
        runCatching {
            val file = File(context.filesDir, "dcapi_package.bin")
            file.writeBytes(packageBytes)
            Log.i(LOG_TAG, "wrote registry blob: ${file.absolutePath} (${packageBytes.size} bytes)")
        }.onFailure { Log.w(LOG_TAG, "blob dump failed", it) }

        val jsonOffset =
            runCatching {
                ByteBuffer.wrap(packageBytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            }.getOrElse {
                Log.w(LOG_TAG, "could not read JSON offset", it)
                return
            }
        if (jsonOffset < 4 || jsonOffset > packageBytes.size) {
            Log.w(LOG_TAG, "implausible JSON offset $jsonOffset for ${packageBytes.size} bytes")
            return
        }
        val text = packageBytes.decodeToString(jsonOffset, packageBytes.size)
        var i = 0
        var part = 0
        val chunk = 3500
        val total = (text.length + chunk - 1) / chunk
        while (i < text.length) {
            val end = (i + chunk).coerceAtMost(text.length)
            Log.i(LOG_TAG, "blob[$part/$total]=${text.substring(i, end)}")
            i = end
            part++
        }
    }

    private fun launcherIcon(): Bitmap {
        val drawable =
            ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
                ?: return Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, ICON_PX, ICON_PX)
        drawable.draw(canvas)
        return bitmap
    }

    private companion object {
        const val LOG_TAG = "DcRegistrySync"
        const val REGISTRY_ID = "elpaso-openid4vp-v1"
        const val DEBOUNCE_MS = 250L
        const val ICON_PX = 32
        const val MATCHER_ASSET = "openid4vp1_0.wasm"
    }
}
