package dev.digitallabor.elpaso.wallet.ui.present

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.digitallabor.elpaso.wallet.R
import dev.digitallabor.elpaso.wallet.ui.common.QrScannerView

private val PRESENTATION_SCHEMES = setOf("openid4vp", "eudi-openid4vp", "haip", "mdoc-openid4vp")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresentScanScreen(
    modifier: Modifier = Modifier,
    onResolved: (uri: String) -> Unit,
    onCancel: () -> Unit,
) {
    var error by remember { mutableStateOf<String?>(null) }
    // Bumping this key remounts QrScannerView so its one-shot `fired` flag resets
    // after a rejected scan — otherwise the camera idles and never re-fires.
    var scanKey by remember { mutableStateOf(0) }
    val rejectedHint = stringResource(R.string.present_scan_not_a_presentation)

    Scaffold(modifier = modifier) { inner ->
        Box(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize(),
        ) {
            key(scanKey) {
                QrScannerView(
                    modifier = Modifier.fillMaxSize(),
                    onScan = { raw ->
                        val parsed = runCatching { Uri.parse(raw) }.getOrNull()
                        val scheme = parsed?.scheme?.lowercase()
                        if (parsed != null && scheme in PRESENTATION_SCHEMES) {
                            onResolved(raw)
                        } else {
                            error = rejectedHint
                            scanKey += 1
                        }
                    },
                )
            }

            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.present_scan_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.detail_back_cd),
                            tint = Color.White,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
            )

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f),
                shadowElevation = 1.dp,
            ) {
                Text(
                    text = error ?: stringResource(R.string.present_scan_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        }
    }
}
