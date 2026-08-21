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

/**
 * Pushes the wallet's credentials (SD-JWT VC and mdoc) into the Android Digital
 * Credentials registry.
 *
 * The platform's sandboxed WASM matcher consumes the registered entries to decide which
 * credential satisfies an incoming OpenID4VP request — that's why we must mirror every
 * disclosable claim here, not just the bare credential.
 *
 * We ship our own matcher binary (see `app/src/main/assets/dcapi_matcher.wasm`, built from
 * `../dcapi-matcher`) instead of the one bundled by `OpenId4VpRegistry`. The custom matcher
 * handles OpenID4VP `transaction_data` (PaSO/TS12) correctly and recognises the mdoc DCQL
 * shape (`doctype_value` + `[namespace, element_id]` paths).
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
        val packageJson = MatcherPackageBuilder.build(credentials, launcherIcon())
        Log.i(
            LOG_TAG,
            "Registering total=${credentials.size} sdjwt=$sdJwtCount mdoc=$mdocCount " +
                "package_bytes=${packageJson.size} matcher_bytes=${matcherWasm.size}",
        )
        dumpPackageForDebug(packageJson)
        val request =
            CustomMatcherRegistry(
                id = REGISTRY_ID,
                credentialsJson = packageJson,
                matcherWasm = matcherWasm,
            )
        runCatching { registryManager.registerCredentials(request) }
            .onSuccess { Log.i(LOG_TAG, "registerCredentials succeeded (${credentials.size} entries)") }
            .onFailure { Log.w(LOG_TAG, "registerCredentials failed", it) }
    }

    /**
     * Mirror the package JSON we're about to register to internal storage so it can be
     * pulled off-device for inspection (`adb pull /data/data/<pkg>/files/dcapi_package.json`),
     * and chunk-log the contents so it's visible in logcat as well. Cheap; logged only at
     * registration time. Keep enabled while we're iterating on matcher schema mismatches.
     */
    private fun dumpPackageForDebug(packageJson: ByteArray) {
        runCatching {
            val file = File(context.filesDir, "dcapi_package.json")
            file.writeBytes(packageJson)
            Log.i(LOG_TAG, "wrote package JSON: ${file.absolutePath} (${packageJson.size} bytes)")
        }.onFailure { Log.w(LOG_TAG, "package dump failed", it) }
        val text = packageJson.decodeToString()
        var i = 0
        var part = 0
        val chunk = 3500
        val total = (text.length + chunk - 1) / chunk
        while (i < text.length) {
            val end = (i + chunk).coerceAtMost(text.length)
            Log.i(LOG_TAG, "package[$part/$total]=${text.substring(i, end)}")
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
        const val MATCHER_ASSET = "dcapi_matcher.wasm"
    }
}
