package dev.digitallabor.elpaso.wallet

import android.app.Application
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.svg.SvgDecoder
import dev.digitallabor.elpaso.wallet.data.settings.LocaleApplier
import dev.digitallabor.elpaso.wallet.data.settings.SettingsRepository
import dev.digitallabor.elpaso.wallet.dcapi.DcIssuanceRegistrySync
import dev.digitallabor.elpaso.wallet.dcapi.DcRegistrySync
import dev.digitallabor.elpaso.wallet.di.appModule
import dev.digitallabor.elpaso.wallet.di.dataModule
import dev.digitallabor.elpaso.wallet.di.issuanceModule
import dev.digitallabor.elpaso.wallet.di.presentationModule
import dev.digitallabor.elpaso.wallet.issuance.CredentialMetadataRefresher
import dev.digitallabor.elpaso.wallet.session.AppLockManager
import dev.digitallabor.elpaso.wallet.session.ForegroundActivityHolder
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import java.security.Security

class ElPasoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
        startKoin {
            androidLogger(Level.INFO)
            androidContext(this@ElPasoApp)
            modules(appModule, dataModule, issuanceModule, presentationModule)
        }
        registerActivityLifecycleCallbacks(get<ForegroundActivityHolder>())
        ProcessLifecycleOwner.get().lifecycle.addObserver(get<AppLockManager>())

        // Apply the persisted UI-language preference before any activity is created.
        // `runBlocking` on a DataStore read is acceptable here — it's microseconds,
        // and we'd otherwise show the wrong locale for the first frame and recreate
        // immediately. The block runs on the main thread but yields after the read.
        val settings = get<SettingsRepository>()
        val pref = runBlocking(Dispatchers.IO) { settings.currentLanguagePreference() }
        LocaleApplier.apply(pref)

        // Sweep stale PaSO credential metadata JWTs at boot. Decoupled from any
        // verifier interaction by design (paso-proof-metadata.md §7 unlinkability).
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { get<CredentialMetadataRefresher>().refreshStale() }
        }

        // Keep the Digital Credentials registry in sync with what the wallet holds.
        // Re-registers whenever credentials change; the platform's WASM matcher uses
        // this snapshot to decide which credential answers a verifier's request.
        val lifecycleOwner: LifecycleOwner = ProcessLifecycleOwner.get()
        get<DcRegistrySync>().start(lifecycleOwner.lifecycleScope)

        // Register the wallet as a DC API *issuance* target so `navigator.credentials.create()`
        // offers "Save to El Paso". Keyed off developerMode only, hence its own sync.
        get<DcIssuanceRegistrySync>().start(lifecycleOwner.lifecycleScope)

        // Coil shares the Koin-managed Ktor client so logo fetches go through the same
        // logging/timeouts as the rest of the wallet.
        val httpClient: HttpClient = get()
        SingletonImageLoader.setSafe { context ->
            ImageLoader
                .Builder(context)
                .components {
                    add(KtorNetworkFetcherFactory(httpClient))
                    // Issuer logos are commonly served as SVG (PASO/EUDI), and a
                    // custom-built ImageLoader doesn't pick up SvgDecoder via
                    // ServiceLoader the way the default loader does.
                    add(SvgDecoder.Factory())
                }.build()
        }
    }
}
