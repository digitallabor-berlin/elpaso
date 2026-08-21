package dev.digitallabor.elpaso.wallet.issuance

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dev.digitallabor.elpaso.wallet.MainActivity
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Receives the OAuth redirect (`elpaso://oauth/callback?code=...&state=...`) and hands the
 * code off to [IssuanceClient], then bounces back to [MainActivity] (which is already at
 * the top of the task because of `launchMode="singleTask"`).
 */
class AuthRedirectActivity : FragmentActivity() {
    private val issuanceClient: IssuanceClient by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val data = intent?.data
        val code = data?.getQueryParameter("code")
        val state = data?.getQueryParameter("state").orEmpty()
        if (code != null) {
            lifecycleScope.launch {
                issuanceClient.completeWithAuthorizationCode(code, state)
            }
        }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
