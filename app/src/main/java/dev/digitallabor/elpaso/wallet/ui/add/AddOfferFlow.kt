package dev.digitallabor.elpaso.wallet.ui.add

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.digitallabor.elpaso.wallet.R
import dev.digitallabor.elpaso.wallet.domain.model.CredentialDisplay
import dev.digitallabor.elpaso.wallet.domain.model.PassArt
import dev.digitallabor.elpaso.wallet.issuance.IssuanceClient
import dev.digitallabor.elpaso.wallet.ui.common.ErrorModal
import dev.digitallabor.elpaso.wallet.ui.common.QrScannerView
import dev.digitallabor.elpaso.wallet.ui.nav.DeepLink
import dev.digitallabor.elpaso.wallet.ui.nav.DeepLinkRouter
import eu.europa.ec.eudi.openid4vci.TxCodeInputMode
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddOfferFlow(
    modifier: Modifier = Modifier,
    incomingOfferUri: String? = null,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val client: IssuanceClient = koinInject()
    val router: DeepLinkRouter = koinInject()
    val state by client.state.collectAsState(initial = IssuanceClient.State.Idle)
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(incomingOfferUri) {
        val uri = incomingOfferUri ?: return@LaunchedEffect
        client.resolveOfferAsync(android.net.Uri.parse(uri))
    }

    val onScanned: (String) -> Unit = { raw ->
        val parsed = runCatching { android.net.Uri.parse(raw) }.getOrNull()
        when (parsed?.scheme?.lowercase()) {
            "openid4vp", "eudi-openid4vp" -> scope.launch {
                router.emit(DeepLink.PresentationRequest(parsed))
            }
            else -> client.resolveOfferAsync(
                parsed ?: android.net.Uri.parse(raw),
            )
        }
    }

    Scaffold(modifier = modifier) { inner ->
        val s = state
        val resolving = s is IssuanceClient.State.Idle && incomingOfferUri != null
        val showScanner = (s is IssuanceClient.State.Idle && !resolving) || s is IssuanceClient.State.Failed

        if (showScanner) {
            // Full-screen camera with overlay chrome (M3 Expressive immersive scanner pattern).
            // Camera is the hero; title sits in a transparent CenterAlignedTopAppBar so the
            // image fills the available content area edge-to-edge. Back IconButton replaces
            // the bottom Cancel button to avoid competing weight centers.
            Box(
                modifier = Modifier
                    .padding(inner)
                    .fillMaxSize(),
            ) {
                QrScannerView(
                    modifier = Modifier.fillMaxSize(),
                    onScan = onScanned,
                )

                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.add_scan_title),
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
                        stringResource(R.string.add_scan_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                }

                if (s is IssuanceClient.State.Failed) {
                    ErrorModal(
                        message = stringResource(R.string.addoffer_failed_generic),
                        technicalDetails = s.message,
                        onDismissRequest = client::reset,
                    )
                }
            }
            return@Scaffold
        }

        Box(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            when (s) {
                IssuanceClient.State.Idle, is IssuanceClient.State.Failed -> {
                    // Resolving an incoming deep-link offer — no scanner shown.
                    Text(
                        stringResource(R.string.addoffer_resolving),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                is IssuanceClient.State.OfferResolved -> {
                    val txReq = (s.grant as? IssuanceClient.GrantOption.PreAuthorized)?.txCode
                    var txCode by remember { mutableStateOf("") }
                    val txCodeOk = txReq == null || (txReq.length?.let { txCode.length == it } ?: txCode.isNotEmpty())
                    LaunchedEffect(s.configurations) {
                        if (selected.isEmpty()) {
                            selected = s.configurations.map { it.configurationId }.toSet()
                        }
                    }
                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(stringResource(R.string.add_consent_title), style = MaterialTheme.typography.headlineSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.addoffer_issuer, s.issuer),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (!s.trusted) {
                            Text(
                                stringResource(R.string.add_unknown_issuer),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        s.configurations.forEach { cfg ->
                            val checked = cfg.configurationId in selected
                            OfferedCredentialCard(
                                cfg = cfg,
                                checked = checked,
                                onToggle = {
                                    selected = if (checked) selected - cfg.configurationId
                                    else selected + cfg.configurationId
                                },
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        if (txReq != null) {
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedTextField(
                                value = txCode,
                                onValueChange = { input ->
                                    val filtered = if (txReq.inputMode == TxCodeInputMode.NUMERIC) {
                                        input.filter { it.isDigit() }
                                    } else input
                                    txCode = txReq.length?.let { filtered.take(it) } ?: filtered
                                },
                                label = { Text(stringResource(R.string.add_tx_code_label)) },
                                supportingText = {
                                    Text(txReq.description ?: stringResource(R.string.add_tx_code_hint))
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = if (txReq.inputMode == TxCodeInputMode.NUMERIC) {
                                        KeyboardType.NumberPassword
                                    } else KeyboardType.Password,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.add_consent_cancel))
                            }
                            Button(
                                enabled = selected.isNotEmpty() && txCodeOk && s.trusted,
                                onClick = {
                                    scope.launch {
                                        client.acceptOffer(
                                            selectedConfigurationIds = selected.toList(),
                                            txCode = txCode.takeIf { txReq != null },
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.add_consent_continue))
                            }
                        }
                    }
                }
                is IssuanceClient.State.AwaitingAuth -> {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    androidx.compose.runtime.LaunchedEffect(s.authUri) {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, s.authUri)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(intent) }
                    }
                    Text(stringResource(R.string.addoffer_browser_continue))
                }
                IssuanceClient.State.Issuing -> Text(stringResource(R.string.addoffer_issuing))
                is IssuanceClient.State.Done -> androidx.compose.runtime.LaunchedEffect(Unit) {
                    client.reset()
                    onDone()
                }
            }
        }
    }
}

@Composable
private fun Row(
    modifier: Modifier,
    horizontalArrangement: Arrangement.HorizontalOrVertical,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier,
        horizontalArrangement = horizontalArrangement,
        content = content,
    )
}

/**
 * Preview card for one credential in the issuance consent dialog. Uses the issuer's
 * `display` block (background color/image, logo, text color, name, description) when
 * advertised; falls back to a deterministic palette otherwise so distinct offered
 * configurations still look apart.
 */
@Composable
private fun OfferedCredentialCard(
    cfg: IssuanceClient.OfferedCredential,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val display = remember(cfg.displayMetadataJson, cfg.displayName) {
        CredentialDisplay.resolve(cfg.displayMetadataJson, cfg.displayName)
    }
    val art = remember(display, cfg.configurationId) {
        PassArt.forDisplay(display, paletteSeed = cfg.configurationId)
    }
    val borderColor = if (checked) {
        MaterialTheme.colorScheme.primary
    } else Color.Transparent
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = if (checked) 6.dp else 2.dp, shape = RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(
            width = if (checked) 2.dp else 0.dp,
            color = borderColor,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .background(brush = art.baseGradient),
        ) {
            // Gradient + sheen only. Issuer-supplied background images often embed their
            // own logos/copy that collide with the name + description we render on top —
            // skip them on this preview surface. Issuer colors still drive the gradient
            // via PassArt.fromDisplay; the logo on the top-right is preserved.
            Box(modifier = Modifier
                .fillMaxSize()
                .background(brush = art.sheenOverlay))
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
                    .padding(end = 64.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = display.name,
                    color = art.foreground,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                )
                display.description?.let {
                    Text(
                        text = it,
                        color = art.foreground.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                    )
                }
                Text(
                    text = cfg.format.name,
                    color = art.foreground.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                )
            }
            display.logoUri?.let { uri ->
                AsyncImage(
                    model = uri,
                    contentDescription = display.name,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(14.dp)
                        .size(36.dp),
                    onError = {
                        Log.w("OfferedCredentialCard", "logo load failed for $uri", it.result.throwable)
                    },
                )
            }
            if (checked) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(4.dp).size(18.dp),
                    )
                }
            }
        }
    }
}
