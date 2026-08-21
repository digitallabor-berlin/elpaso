package dev.digitallabor.elpaso.wallet

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dev.digitallabor.elpaso.wallet.data.settings.LocaleApplier
import dev.digitallabor.elpaso.wallet.data.settings.SettingsRepository
import dev.digitallabor.elpaso.wallet.ui.WalletApp
import dev.digitallabor.elpaso.wallet.ui.nav.DeepLink
import dev.digitallabor.elpaso.wallet.ui.nav.DeepLinkRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject

class MainActivity : FragmentActivity() {
    private val router: DeepLinkRouter by inject()
    private val settings: SettingsRepository by inject()

    /**
     * Apply the persisted UI language to this activity's resources *before* anything
     * else inflates. AppCompatDelegate.setApplicationLocales doesn't auto-swap the
     * Configuration for a non-AppCompatActivity, so we wrap the base context
     * manually. The picker triggers `recreate()`, which re-enters this method.
     */
    override fun attachBaseContext(newBase: Context) {
        val repo: SettingsRepository = get()
        val pref = runBlocking(Dispatchers.IO) { repo.currentLanguagePreference() }
        super.attachBaseContext(LocaleApplier.wrap(newBase, pref))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Defensive: re-publish the locale through AppCompatDelegate too, so the
        // Android 13+ system per-app language picker stays in sync.
        LocaleApplier.apply(runBlocking(Dispatchers.IO) { settings.currentLanguagePreference() })
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { WalletApp() }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        val link = when (data.scheme) {
            "openid-credential-offer", "haip" -> DeepLink.CredentialOffer(data)
            "openid4vp", "eudi-openid4vp" -> DeepLink.PresentationRequest(data)
            "https" -> if (data.host == "wallet.example.com") DeepLink.CredentialOffer(data) else null
            else -> null
        } ?: return
        lifecycleScope.launch { router.emit(link) }
    }
}
