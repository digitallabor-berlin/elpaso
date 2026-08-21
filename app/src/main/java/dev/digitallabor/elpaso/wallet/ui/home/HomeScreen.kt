package dev.digitallabor.elpaso.wallet.ui.home

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import dev.digitallabor.elpaso.wallet.R
import dev.digitallabor.elpaso.wallet.domain.model.Credential
import dev.digitallabor.elpaso.wallet.domain.model.CredentialDisplay
import dev.digitallabor.elpaso.wallet.domain.model.PassArt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

/**
 * Upward velocity (px/s) required to interpret a long-press drag release as a
 * "present this credential" fling rather than a reorder. Tuned to land between a
 * deliberate flick (~2000+) and an accidental release after a slow reorder.
 */
private const val PRESENT_FLING_THRESHOLD_PX_PER_SEC = 1800f

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = koinViewModel(),
    onAdd: () -> Unit,
    onOpenCredential: (String) -> Unit,
    onPresentCredential: (String) -> Unit,
) {
    val items by viewModel.items.collectAsState(initial = emptyList())

    DeviceTiltProvider {
        Scaffold(
            modifier = modifier,
            contentWindowInsets =
                androidx.compose.foundation.layout
                    .WindowInsets(0, 0, 0, 0),
            floatingActionButton = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LargeFloatingActionButton(
                        onClick = onAdd,
                        shape = RoundedCornerShape(28.dp),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.home_fab_cd),
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        ) { inner ->
            if (items.isEmpty()) {
                EmptyState(modifier = Modifier.padding(inner), onAdd = onAdd)
            } else {
                PassDeck(
                    modifier = Modifier.padding(inner),
                    items = items,
                    onOpenCredential = onOpenCredential,
                    onReorder = viewModel::setOrder,
                    onPresentCredential = onPresentCredential,
                )
            }
        }
    }
}

@Composable
private fun PassDeck(
    modifier: Modifier,
    items: List<Credential>,
    onOpenCredential: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    onPresentCredential: (String) -> Unit,
) {
    val scroll = rememberScrollState()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(scroll),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(84.dp)) // padding for the floating logo on top left
        DraggableDeck(
            items = items,
            onOpenCredential = onOpenCredential,
            onReorder = onReorder,
            onPresentCredential = onPresentCredential,
        )
        Spacer(modifier = Modifier.height(120.dp))
    }
}

/**
 * Stacked, reorderable deck of credential passes. Tap to open, long-press to grab and
 * drag to a new position. The order is owned by [HomeViewModel] / [dev.digitallabor.elpaso.wallet.data.settings.SettingsRepository];
 * we mirror it into a local mutable list so drag visuals can update at 60 fps without
 * round-tripping through DataStore.
 */
@Composable
private fun DraggableDeck(
    items: List<Credential>,
    onOpenCredential: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    onPresentCredential: (String) -> Unit,
) {
    val peekHeight = 96.dp
    val expandedHeight = 220.dp
    // Forward step between adjacent cards: only the top `peekHeight + gap` of each
    // non-last card is exposed, matching the previous Column(spacedBy(negative)) look.
    val pitch = peekHeight + 12.dp
    val density = LocalDensity.current
    val pitchPx = with(density) { pitch.toPx() }
    val haptics = LocalHapticFeedback.current
    val configuration = LocalConfiguration.current
    // Fly-out target: full screen height plus one card height so the card fully clears
    // the top edge regardless of where in the deck it started.
    val flyAwayPx = with(density) { (configuration.screenHeightDp.dp + expandedHeight).toPx() }

    // Local mirror of `items` keyed by id. Reorders mutate this list optimistically;
    // we push the resulting order to the ViewModel on drag end. If `items` changes
    // from upstream (e.g. add/remove), we resync.
    val order = remember { mutableStateListOf<Credential>() }
    LaunchedEffect(items) {
        snapshotFlow { items }.distinctUntilChanged().collect { latest ->
            // Preserve the user-driven order for ids that are still present; append
            // newcomers (in their incoming order) and drop removed entries.
            val incomingById = latest.associateBy { it.id }
            val preserved = order.mapNotNull { incomingById[it.id] }
            val preservedIds = preserved.mapTo(mutableSetOf()) { it.id }
            val newcomers = latest.filter { it.id !in preservedIds }
            order.clear()
            order.addAll(preserved + newcomers)
        }
    }

    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    // Snapshot of `order` at long-press. Used to undo mid-drag visual swaps when the
    // release turns out to be a fling — a flung pass returns to its original slot,
    // so the in-between shuffles other cards did must be rewound.
    var dragStartOrder by remember { mutableStateOf<List<Credential>>(emptyList()) }
    var dragStartIndex by remember { mutableStateOf(-1) }

    val totalHeight =
        if (order.isEmpty()) {
            0.dp
        } else {
            pitch * (order.size - 1) + expandedHeight
        }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(totalHeight),
    ) {
        order.forEachIndexed { index, item ->
            key(item.id) {
                val isDragged = item.id == draggedId
                val isLast = index == order.lastIndex
                val baseY = index * pitchPx
                val animatedY by animateFloatAsState(
                    targetValue = baseY,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "cardY",
                )
                val cardHeight by animateDpAsState(
                    targetValue = expandedHeight,
                    animationSpec = tween(300),
                    label = "cardHeight",
                )

                // Per-card fling state. Reset on composition exit, so backing out
                // of the scanner returns the pass to its slot automatically — the
                // 1.5 s delay below only matters when HomeScreen stays composed.
                val velocityTracker = remember { VelocityTracker() }
                val flyOffset = remember { Animatable(0f) }
                var isFlying by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()

                val yPx =
                    when {
                        // While flying, bypass the spring on `animatedY` — order may have
                        // been just restored, so `animatedY` is mid-spring toward a stale
                        // target. `baseY` is the fresh, restored slot anchor.
                        isFlying -> baseY + flyOffset.value

                        isDragged -> baseY + dragOffsetY

                        else -> animatedY
                    }

                val onClick: () -> Unit = { onOpenCredential(item.id) }

                PassCard(
                    item = item,
                    showFull = isLast || isDragged,
                    modifier =
                        Modifier
                            // zIndex governs paint order inside the parent layout
                            // regardless of declaration order — without this, cards
                            // declared after the dragged one paint over it as the
                            // user moves it down through the stack.
                            .zIndex(if (isDragged || isFlying) 1f else 0f)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height(cardHeight)
                            .offset { IntOffset(0, yPx.roundToInt()) }
                            // Pop the dragged card visually: lift on the Z axis, nudge scale.
                            .graphicsLayer {
                                if (isDragged) {
                                    shadowElevation = with(density) { 16.dp.toPx() }
                                    scaleX = 1.03f
                                    scaleY = 1.03f
                                }
                            }.pointerInput(item.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggedId = item.id
                                        dragOffsetY = 0f
                                        dragStartOrder = order.toList()
                                        dragStartIndex = order.indexOfFirst { it.id == item.id }
                                        velocityTracker.resetTracking()
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDrag = { change, drag ->
                                        velocityTracker.addPointerInputChange(change)
                                        change.consume()
                                        dragOffsetY += drag.y
                                        // Translate accumulated drag into an index delta and
                                        // rebase the offset so the rendered position stays
                                        // continuous after the swap.
                                        val currentIndex = order.indexOfFirst { it.id == item.id }
                                        if (currentIndex == -1) return@detectDragGesturesAfterLongPress
                                        val targetIndex =
                                            (currentIndex + (dragOffsetY / pitchPx).roundToInt())
                                                .coerceIn(0, order.lastIndex)
                                        if (targetIndex != currentIndex) {
                                            order.move(currentIndex, targetIndex)
                                            dragOffsetY -= (targetIndex - currentIndex) * pitchPx
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    },
                                    onDragEnd = {
                                        val vy = velocityTracker.calculateVelocity().y
                                        velocityTracker.resetTracking()
                                        if (vy < -PRESENT_FLING_THRESHOLD_PX_PER_SEC) {
                                            // Compute the on-screen release position before
                                            // restoring order — `baseY` here reflects the
                                            // shuffled-during-drag index.
                                            val currentIndex = order.indexOfFirst { it.id == item.id }
                                            val originalIndex =
                                                dragStartIndex.takeIf { it >= 0 }
                                                    ?: currentIndex
                                            val releaseVisualY =
                                                currentIndex * pitchPx + dragOffsetY
                                            // Roll back any mid-drag swaps so other cards
                                            // settle back to their pre-fling positions, and
                                            // the persisted order is not touched.
                                            if (dragStartOrder.isNotEmpty()) {
                                                order.clear()
                                                order.addAll(dragStartOrder)
                                            }
                                            val flyStart = releaseVisualY - originalIndex * pitchPx
                                            draggedId = null
                                            dragOffsetY = 0f
                                            isFlying = true
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            scope.launch {
                                                flyOffset.snapTo(flyStart)
                                                flyOffset.animateTo(
                                                    targetValue = -flyAwayPx,
                                                    animationSpec =
                                                        tween(
                                                            durationMillis = 220,
                                                            easing = FastOutLinearInEasing,
                                                        ),
                                                )
                                                onPresentCredential(item.id)
                                                delay(1500)
                                                flyOffset.snapTo(0f)
                                                isFlying = false
                                            }
                                        } else {
                                            draggedId = null
                                            dragOffsetY = 0f
                                            onReorder(order.map { it.id })
                                        }
                                        dragStartOrder = emptyList()
                                        dragStartIndex = -1
                                    },
                                    onDragCancel = {
                                        velocityTracker.resetTracking()
                                        draggedId = null
                                        dragOffsetY = 0f
                                        onReorder(order.map { it.id })
                                        dragStartOrder = emptyList()
                                        dragStartIndex = -1
                                    },
                                )
                            }.clickable(enabled = !isFlying, onClick = onClick),
                )
            }
        }
    }
}

private fun <T> MutableList<T>.move(
    from: Int,
    to: Int,
) {
    if (from == to) return
    val item = removeAt(from)
    add(to, item)
}

@Composable
private fun PassCard(
    item: Credential,
    showFull: Boolean,
    modifier: Modifier = Modifier,
) {
    CredentialPassCard(item, showFull, modifier)
}

@Composable
private fun CredentialPassCard(
    credential: Credential,
    showFull: Boolean,
    modifier: Modifier = Modifier,
) {
    val display = CredentialDisplay.resolve(credential)
    val art = PassArt.forCredential(credential)
    PassCardShell(
        art = art,
        showFull = showFull,
        modifier = modifier,
        trailing = {
            display.logoUri?.let { uri ->
                AsyncImage(
                    model = uri,
                    contentDescription = display.name,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .size(40.dp),
                    onError = { Log.w("PassCardLogo", "Coil failed for $uri", it.result.throwable) },
                )
            }
        },
    ) {
        Text(
            text = display.name,
            color = art.foreground,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
        )
        display.description?.let {
            Text(
                text = it,
                color = art.foreground.copy(alpha = 0.85f),
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun PassCardShell(
    art: PassArt,
    showFull: Boolean,
    modifier: Modifier,
    trailing: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Surface(
        modifier =
            modifier
                .shadow(elevation = if (showFull) 8.dp else 2.dp, shape = RoundedCornerShape(28.dp)),
        shape = RoundedCornerShape(28.dp),
        color = Color.Transparent,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(brush = art.baseGradient)
                    .glossyShine(),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                        .padding(end = 64.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
            trailing()
        }
    }
}

@Composable
private fun EmptyState(
    modifier: Modifier,
    onAdd: () -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.AccountBalanceWallet,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.home_empty_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.home_empty_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onAdd,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 32.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Text(
                text = stringResource(R.string.home_add),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
