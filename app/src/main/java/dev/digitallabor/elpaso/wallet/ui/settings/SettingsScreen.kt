package dev.digitallabor.elpaso.wallet.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.digitallabor.elpaso.wallet.BuildConfig
import dev.digitallabor.elpaso.wallet.R
import dev.digitallabor.elpaso.wallet.data.settings.LanguagePreference
import dev.digitallabor.elpaso.wallet.data.settings.MetadataCacheTtl
import dev.digitallabor.elpaso.wallet.data.settings.ThemePreference
import dev.digitallabor.elpaso.wallet.data.trust.TrustListService
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    trustList: TrustListService = koinInject(),
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    // Trigger a clean activity recreation after a language change so the new locale
    // takes effect across every screen, not just the picker.
    LaunchedEffect(viewModel) {
        viewModel.recreateRequest.collect {
            (context as? Activity)?.recreate()
        }
    }
    val devMode by viewModel.developerMode.collectAsState()
    val themePref by viewModel.themePreference.collectAsState()
    val languagePref by viewModel.languagePreference.collectAsState()
    val metadataCacheEnabled by viewModel.metadataCacheEnabled.collectAsState()
    val metadataCacheTtl by viewModel.metadataCacheTtl.collectAsState()

    // LargeTopAppBar matches PassDetailScreen — expressive headline on first render,
    // collapses to a compact bar on scroll. Settings was the only main screen still
    // rendering its title inline.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { inner ->
        Column(
            modifier =
                Modifier
                    .padding(inner)
                    .fillMaxSize()
                    // WalletApp's outer Scaffold uses the default contentWindowInsets which
                    // does NOT include the IME — `inner` here doesn't react to the soft
                    // keyboard. Add imePadding BEFORE verticalScroll so the scroll viewport
                    // shrinks when the keyboard opens; that lets any focused OutlinedTextField
                    // auto-scroll into view via its built-in BringIntoViewRequester.
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsCard {
                SectionHeader(
                    title = stringResource(R.string.settings_about),
                    summary = null,
                )
                Text(
                    text = stringResource(R.string.settings_about_app_name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text =
                        stringResource(
                            R.string.settings_about_version,
                            BuildConfig.VERSION_NAME,
                            BuildConfig.VERSION_CODE,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.settings_about_copyright) + " " + stringResource(R.string.settings_about_publisher),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.settings_about_disclaimer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsCard {
                SectionHeader(
                    title = stringResource(R.string.settings_appearance),
                    summary = stringResource(R.string.settings_appearance_summary),
                )
                ThemeSelector(
                    current = themePref,
                    onSelect = viewModel::setThemePreference,
                )
            }

            SettingsCard {
                SectionHeader(
                    title = stringResource(R.string.settings_language),
                    summary = stringResource(R.string.settings_language_summary),
                )
                LanguageSelector(
                    current = languagePref,
                    onSelect = viewModel::setLanguagePreference,
                )
            }

            SettingsCard {
                SectionHeader(
                    title = stringResource(R.string.settings_developer_section),
                    summary = stringResource(R.string.settings_developer_mode_summary),
                )
                ToggleRow(
                    label = stringResource(R.string.settings_developer_mode),
                    checked = devMode,
                    onCheckedChange = viewModel::setDeveloperMode,
                )
            }

            SettingsCard {
                SectionHeader(
                    title = stringResource(R.string.settings_metadata_cache_section),
                    summary = stringResource(R.string.settings_metadata_cache_summary),
                )
                ToggleRow(
                    label = stringResource(R.string.settings_metadata_cache_enabled),
                    checked = metadataCacheEnabled,
                    onCheckedChange = viewModel::setMetadataCacheEnabled,
                )
                if (metadataCacheEnabled) {
                    Text(
                        stringResource(R.string.settings_metadata_cache_ttl_label),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    MetadataCacheTtlSelector(
                        current = metadataCacheTtl,
                        onSelect = viewModel::setMetadataCacheTtl,
                    )
                }
                OutlinedButton(
                    onClick = viewModel::clearMetadataCache,
                    colors =
                        ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Text(stringResource(R.string.settings_metadata_cache_clear))
                }
            }

            SettingsCard {
                SectionHeader(
                    title = stringResource(R.string.settings_dc_api),
                    summary = stringResource(R.string.settings_dc_api_summary),
                )
                FilledTonalButton(onClick = {
                    // Primary: jump straight to the per-app credential-provider picker
                    // (Android 14+). Fallback: this app's own details page — the global
                    // Settings menu was useless here since nothing the user could toggle
                    // there actually authorises the wallet as a credential provider.
                    val primary =
                        Intent(Settings.ACTION_CREDENTIAL_PROVIDER)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(primary) }
                        .recoverCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                }) {
                    Text(stringResource(R.string.settings_dc_api_enable))
                }
            }

            SettingsCard {
                SectionHeader(
                    title = stringResource(R.string.settings_trusted_issuers),
                    summary = null,
                )
                trustList.listIssuers().forEach { issuer ->
                    ListItem(
                        headlineContent = { Text(issuer.label) },
                        supportingContent = { Text(issuer.id, style = MaterialTheme.typography.bodySmall) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }

            SettingsCard {
                SectionHeader(
                    title = stringResource(R.string.settings_trusted_verifiers),
                    summary = null,
                )
                trustList.listVerifiers().forEach { v ->
                    ListItem(
                        headlineContent = { Text(v.label) },
                        supportingContent = { Text(v.id, style = MaterialTheme.typography.bodySmall) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * Tonal section card aligned with the wallet's expressive primitives — 24dp corners and
 * `surfaceContainerLow`, the same tonal layering the credential pass deck uses. Replaces
 * the prior divider-separated flat layout so each settings group reads as its own
 * contained surface.
 */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    summary: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (summary != null) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSelector(
    current: LanguagePreference,
    onSelect: (LanguagePreference) -> Unit,
) {
    val options =
        listOf(
            LanguagePreference.System to R.string.settings_language_system,
            LanguagePreference.English to R.string.settings_language_english,
            LanguagePreference.German to R.string.settings_language_german,
            LanguagePreference.French to R.string.settings_language_french,
        )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (pref, labelRes) ->
            SegmentedButton(
                selected = pref == current,
                onClick = { onSelect(pref) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(stringResource(labelRes))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSelector(
    current: ThemePreference,
    onSelect: (ThemePreference) -> Unit,
) {
    val options =
        listOf(
            ThemePreference.System to R.string.settings_theme_system,
            ThemePreference.Light to R.string.settings_theme_light,
            ThemePreference.Dark to R.string.settings_theme_dark,
        )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (pref, labelRes) ->
            SegmentedButton(
                selected = pref == current,
                onClick = { onSelect(pref) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(stringResource(labelRes))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetadataCacheTtlSelector(
    current: MetadataCacheTtl,
    onSelect: (MetadataCacheTtl) -> Unit,
) {
    val options =
        listOf(
            MetadataCacheTtl.OneHour to R.string.settings_metadata_cache_ttl_hour,
            MetadataCacheTtl.OneDay to R.string.settings_metadata_cache_ttl_day,
            MetadataCacheTtl.OneWeek to R.string.settings_metadata_cache_ttl_week,
            MetadataCacheTtl.JwtExpiry to R.string.settings_metadata_cache_ttl_jwt,
        )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (ttl, labelRes) ->
            SegmentedButton(
                selected = ttl == current,
                onClick = { onSelect(ttl) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(stringResource(labelRes))
            }
        }
    }
}
