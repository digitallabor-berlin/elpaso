package dev.digitallabor.elpaso.wallet.dcapi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.credentials.DigitalCredential
import androidx.credentials.ExperimentalDigitalCredentialApi
import androidx.credentials.registry.provider.RegisterCreationOptionsRequest
import androidx.credentials.registry.provider.RegistryManager
import dev.digitallabor.elpaso.wallet.R
import dev.digitallabor.elpaso.wallet.data.settings.SettingsRepository
import dev.digitallabor.elpaso.wallet.data.trust.TrustListService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Registers the wallet as a Digital Credentials API *issuance* target, so a browser calling
 * `navigator.credentials.create()` with an OpenID4VCI request is offered "Save to El Paso".
 *
 * Deliberately separate from [DcRegistrySync]: that one re-registers whenever the credential
 * set changes, whereas creation options depend only on [SettingsRepository.developerMode] and
 * the static trust list.
 *
 * The allowlist mirrors the in-app trust gate in `IssuanceClient.resolveOffer`
 * (`developerMode || isIssuerTrusted(issuerId)`) so the two cannot disagree. In developer
 * mode the `capabilities` key is omitted, which the matcher reads as "any issuer"; untrusted
 * issuers are then warned about in-app rather than hidden. With developer mode off, only
 * trusted issuers can see the wallet at all.
 */
@OptIn(ExperimentalDigitalCredentialApi::class)
class DcIssuanceRegistrySync(
    private val context: Context,
    private val settings: SettingsRepository,
    private val trustList: TrustListService,
) {
    private val registryManager by lazy { RegistryManager.create(context) }

    private val matcherWasm: ByteArray by lazy {
        context.assets.open(MATCHER_ASSET).use { it.readBytes() }
    }

    /**
     * Re-registers on every [SettingsRepository.developerMode] change. The flow emits its
     * current value on collection, so this also performs the initial registration.
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            settings.developerMode.distinctUntilChanged().collect { developerMode ->
                register(developerMode)
            }
        }
    }

    private suspend fun register(developerMode: Boolean) {
        val allowlist = if (developerMode) null else trustList.listIssuers().map { it.id }
        val blob =
            DcIssuanceRegistryBlob.build(
                iconPng = launcherIconPng(),
                title = context.getString(R.string.app_name),
                subtitle = context.getString(R.string.dc_issuance_save_subtitle),
                issuerAllowlist = allowlist,
            )
        Log.i(
            LOG_TAG,
            "Registering creation options developer_mode=$developerMode " +
                "allowlist=${allowlist?.size ?: "any"} blob_bytes=${blob.size} " +
                "matcher_bytes=${matcherWasm.size}",
        )
        // Creation-options support depends on the device's Play Services version. An
        // unsupported device must degrade to "the wallet is not offered", never a crash.
        runCatching {
            registryManager.registerCreationOptions(
                object : RegisterCreationOptionsRequest(
                    type = DigitalCredential.TYPE_DIGITAL_CREDENTIAL,
                    id = REGISTRY_ID,
                    creationOptions = blob,
                    matcher = matcherWasm,
                    intentAction = "",
                ) {},
            )
        }.onSuccess { Log.i(LOG_TAG, "registerCreationOptions succeeded") }
            .onFailure { Log.w(LOG_TAG, "registerCreationOptions failed", it) }
    }

    private fun launcherIconPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.let { drawable ->
            drawable.setBounds(0, 0, ICON_PX, ICON_PX)
            drawable.draw(Canvas(bitmap))
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    private companion object {
        const val LOG_TAG = "DcIssuanceRegistry"

        /** Must match what the matcher and CMWallet register under. */
        const val REGISTRY_ID = "openid4vci"
        const val ICON_PX = 96
        const val MATCHER_ASSET = "dc_issuance_matcher.wasm"
    }
}
