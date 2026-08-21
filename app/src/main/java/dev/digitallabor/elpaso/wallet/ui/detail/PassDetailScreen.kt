package dev.digitallabor.elpaso.wallet.ui.detail

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.digitallabor.elpaso.wallet.R
import dev.digitallabor.elpaso.wallet.domain.model.CredentialDisplay
import dev.digitallabor.elpaso.wallet.domain.model.Format
import dev.digitallabor.elpaso.wallet.domain.model.PassArt
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.koin.androidx.compose.koinViewModel
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassDetailScreen(
    modifier: Modifier = Modifier,
    credentialId: String,
    onBack: () -> Unit,
    onUse: () -> Unit,
    viewModel: PassDetailViewModel = koinViewModel(),
) {
    var state by remember { mutableStateOf<PassDetailViewModel.State?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(credentialId) { state = viewModel.load(credentialId) }

    val current = state
    val display = current?.let { CredentialDisplay.resolve(it.credential) }
    val topTitle = display?.name.orEmpty()
    val issuerSubtitle = current?.let { issuerHost(it.credential.issuerId) }.orEmpty()
    // LargeTopAppBar gives us the expressive headline on first render and collapses to a
    // small bar on scroll (exit-until-collapsed). The flexible-with-subtitle variants
    // ship in material3 1.4.0 but are still `internal` — until they go public we render
    // the issuer subtitle inside the title slot, which is the same visual outcome.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(
                            text = topTitle,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        if (issuerSubtitle.isNotBlank()) {
                            Text(
                                text = issuerSubtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.detail_back_cd),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { inner ->
        if (current == null || display == null) {
            Box(modifier = Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.detail_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            val credential = current.credential
            val art = PassArt.forCredential(credential)
            Column(
                modifier = Modifier
                    .padding(inner)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                HeroCard(display = display, art = art)
                ActionRow(
                    onUse = onUse,
                    onRemove = { scope.launch { viewModel.remove(credential); onBack() } },
                )
                ClaimsCard(claims = current.claims.user)
                TechnicalDetailsCard(format = credential.format, claims = current.claims.protocol)
            }
        }
    }
}

@Composable
private fun HeroCard(display: CredentialDisplay, art: PassArt) {
    // Hero moment #1 of 1 for this screen: bold corners (32dp = shape.extraLarge-increased),
    // gradient + sheen + optional issuer-supplied background image. Issuer-supplied colors
    // win when present (see PassArt.fromDisplay); otherwise the deterministic palette
    // keeps cards distinct across issuers.
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(RoundedCornerShape(32.dp)),
        shape = RoundedCornerShape(32.dp),
        color = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Gradient + sheen only. Issuer-supplied background images often embed their
            // own logos and copy, which collide with the credential name, description and
            // logo we render on top — net effect was a busy, illegible hero. Logos stay
            // (rendered top-right below) since those are designed to sit alone on a
            // colored surface. Issuer-supplied colors still drive the gradient via
            // PassArt.fromDisplay.
            Box(modifier = Modifier
                .fillMaxSize()
                .background(brush = art.baseGradient))
            Box(modifier = Modifier
                .fillMaxSize()
                .background(brush = art.sheenOverlay))
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .padding(end = 84.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = display.name,
                    color = art.foreground,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                display.description?.let {
                    Text(
                        text = it,
                        color = art.foreground.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            display.logoUri?.let { uri ->
                AsyncImage(
                    model = uri,
                    contentDescription = display.name,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(20.dp)
                        .size(56.dp),
                    onError = { Log.w("PassDetailLogo", "Coil failed for $uri", it.result.throwable) },
                )
            }
        }
    }
}

@Composable
private fun ActionRow(onUse: () -> Unit, onRemove: () -> Unit) {
    // Expressive pairing: one filled primary (high emphasis, the page's main action) and
    // one filled-tonal secondary. Both at L-size height (64dp) with bold-but-not-pill
    // corners (20dp) for shape contrast against the 32dp hero card.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = onUse,
            modifier = Modifier
                .weight(1f)
                .height(64.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text(
                text = stringResource(R.string.detail_use),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        FilledTonalButton(
            onClick = onRemove,
            modifier = Modifier
                .weight(1f)
                .height(64.dp),
            shape = RoundedCornerShape(20.dp),
        ) {
            Text(
                text = stringResource(R.string.detail_remove),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ClaimsCard(claims: JsonObject) {
    SectionHeading(text = stringResource(R.string.detail_details))
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        if (claims.isEmpty()) {
            Text(
                text = stringResource(R.string.detail_claims_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                ClaimEntries(entries = claims.entries.toList(), depth = 0)
            }
        }
    }
}

@Composable
private fun TechnicalDetailsCard(format: Format, claims: JsonObject) {
    var expanded by remember { mutableStateOf(false) }
    // Tonal surface for "supporting info" hierarchy: lives below the primary hero +
    // claims, uses surfaceContainerLow with a more restrained 20dp corner so it reads
    // as secondary even when expanded. Click target spans the full row.
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.detail_technical_details),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(expandFrom = Alignment.Top),
                exit = shrinkVertically(shrinkTowards = Alignment.Top),
            ) {
                Column(modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                    ClaimEntryRow(label = "format", value = JsonPrimitive(format.name), depth = 0)
                    if (claims.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        ClaimEntries(entries = claims.entries.toList(), depth = 0)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
    )
}

private const val MAX_CLAIM_DEPTH = 3

@Composable
private fun ClaimEntries(entries: List<Map.Entry<String, JsonElement>>, depth: Int) {
    entries.forEachIndexed { index, entry ->
        ClaimEntryRow(label = entry.key, value = entry.value, depth = depth)
        if (index < entries.size - 1) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun ClaimEntryRow(label: String, value: JsonElement, depth: Int) {
    val indent = (depth * 12).dp
    val locale = LocalConfiguration.current.locales[0]
    when {
        value is JsonObject && depth < MAX_CLAIM_DEPTH -> {
            Column(modifier = Modifier
                .fillMaxWidth()
                .padding(start = indent + 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                ClaimEntries(entries = value.entries.toList(), depth = depth + 1)
            }
        }
        value is JsonArray && value.any { it is JsonObject } && depth < MAX_CLAIM_DEPTH -> {
            Column(modifier = Modifier
                .fillMaxWidth()
                .padding(start = indent + 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                value.forEachIndexed { i, element ->
                    if (element is JsonObject) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "[$i]",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                        ClaimEntries(entries = element.entries.toList(), depth = depth + 2)
                    } else {
                        ClaimEntryRow(label = "[$i]", value = element, depth = depth + 1)
                    }
                }
            }
        }
        else -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = indent + 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatValue(label, value, locale),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1.4f),
                )
            }
        }
    }
}

private val TIMESTAMP_CLAIM_KEYS = setOf("iat", "exp", "nbf")

private fun formatValue(label: String, value: JsonElement, locale: Locale): String {
    if (label in TIMESTAMP_CLAIM_KEYS && value is JsonPrimitive) {
        value.longOrNull?.let { return formatEpochSeconds(it, locale) }
    }
    return when (value) {
        is JsonNull -> "—"
        is JsonPrimitive -> value.content
        is JsonArray -> value.joinToString(", ") { element ->
            if (element is JsonPrimitive) element.content else element.toString()
        }
        is JsonObject -> value.toString()
    }
}

private fun formatEpochSeconds(epochSeconds: Long, locale: Locale): String =
    DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(locale)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(epochSeconds))

private fun issuerHost(issuerId: String): String =
    runCatching { URI.create(issuerId).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: issuerId
