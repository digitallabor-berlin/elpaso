package dev.digitallabor.elpaso.wallet.ui.common

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.digitallabor.elpaso.wallet.R
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Embeds a CameraX preview that streams frames through ML Kit's barcode scanner.
 * Invokes [onScan] once with the first decoded QR `displayValue` (typically a URI string).
 * The overlay reticle gives the user a clear aiming target and flashes briefly when a
 * code is recognised; a haptic confirms detection before [onScan] fires.
 */
// `ImageProxy.image` is behind CameraX's ExperimentalGetImage opt-in. ML Kit's
// InputImage.fromMediaImage needs that underlying android.media.Image, so the
// opt-in is unavoidable here; scoped to this composable rather than the module.
//
// This must be androidx.annotation.OptIn, not kotlin.OptIn: ExperimentalGetImage is
// a Java @RequiresOptIn marker, and the UnsafeOptInUsageError lint check only
// recognises the androidx annotation.
@androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
@Composable
fun QrScannerView(
    modifier: Modifier = Modifier,
    onScan: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher =
        androidx.activity.compose.rememberLauncherForActivityResult(
            contract =
                androidx.activity.result.contract.ActivityResultContracts
                    .RequestPermission(),
            onResult = { hasPermission = it },
        )

    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    Box(modifier = modifier) {
        if (!hasPermission) {
            Text(stringResource(R.string.qr_permission_required))
            return@Box
        }
        val executor = remember { Executors.newSingleThreadExecutor() }
        val scanner = remember { BarcodeScanning.getClient() }
        var fired by remember { mutableStateOf(false) }
        var detected by remember { mutableStateOf(false) }
        var pendingValue by remember { mutableStateOf<String?>(null) }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview =
                        Preview
                            .Builder()
                            .build()
                            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analysis =
                        ImageAnalysis
                            .Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                    analysis.setAnalyzer(executor) { proxy ->
                        val media = proxy.image
                        if (media != null && !fired) {
                            val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                            scanner
                                .process(input)
                                .addOnSuccessListener { barcodes ->
                                    val first =
                                        barcodes.firstOrNull {
                                            it.format == Barcode.FORMAT_QR_CODE && it.displayValue != null
                                        }
                                    val value = first?.displayValue
                                    if (value != null) {
                                        fired = true
                                        pendingValue = value
                                        detected = true
                                    }
                                }.addOnCompleteListener { proxy.close() }
                        } else {
                            proxy.close()
                        }
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
        )

        ScannerOverlay(detected = detected)

        LaunchedEffect(detected) {
            if (detected) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                delay(180)
                pendingValue?.let(onScan)
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                scanner.close()
                executor.shutdown()
            }
        }
    }
}

@Composable
private fun ScannerOverlay(detected: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    val cornerColor by animateColorAsState(
        targetValue = if (detected) primary else Color.White.copy(alpha = 0.9f),
        animationSpec = tween(180),
        label = "scannerCornerColor",
    )
    val cornerScale by animateFloatAsState(
        targetValue = if (detected) 1.06f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "scannerCornerScale",
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val reticleDp = min(maxWidth.value, maxHeight.value) * 0.70f
        val reticleSize = (if (reticleDp > 280f) 280f else reticleDp).dp
        val cornerRadius = 24.dp
        val bracketLen = 28.dp
        val strokeWidth = 4.dp

        // Dimmed mask with a transparent rounded-square cutout.
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            drawRect(Color.Black.copy(alpha = 0.55f))
            val s = reticleSize.toPx()
            val r = cornerRadius.toPx()
            val tlX = (size.width - s) / 2f
            val tlY = (size.height - s) / 2f
            drawRoundRect(
                color = Color.Transparent,
                topLeft = Offset(tlX, tlY),
                size = Size(s, s),
                cornerRadius = CornerRadius(r, r),
                blendMode = BlendMode.Clear,
            )
        }

        // Corner brackets hugging the rounded cutout. Separate canvas so the scale pulse
        // only animates the brackets, not the mask.
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = cornerScale
                        scaleY = cornerScale
                        transformOrigin = TransformOrigin.Center
                    },
        ) {
            val s = reticleSize.toPx()
            val r = cornerRadius.toPx()
            val len = bracketLen.toPx()
            val w = strokeWidth.toPx()
            val tlX = (size.width - s) / 2f
            val tlY = (size.height - s) / 2f
            val brX = tlX + s
            val brY = tlY + s
            val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)

            drawPath(bracketPath(tlX, tlY, r, len, Corner.TopLeft), cornerColor, style = stroke)
            drawPath(bracketPath(brX, tlY, r, len, Corner.TopRight), cornerColor, style = stroke)
            drawPath(bracketPath(brX, brY, r, len, Corner.BottomRight), cornerColor, style = stroke)
            drawPath(bracketPath(tlX, brY, r, len, Corner.BottomLeft), cornerColor, style = stroke)
        }
    }
}

private enum class Corner { TopLeft, TopRight, BottomRight, BottomLeft }

/**
 * Builds a single L-shaped corner bracket whose elbow traces the cutout's rounded corner.
 * [cornerX]/[cornerY] is the un-rounded corner of the cutout's bounding box; arms extend
 * inward along the two adjacent edges for [len] pixels past the rounded curve of radius [r].
 */
private fun bracketPath(
    cornerX: Float,
    cornerY: Float,
    r: Float,
    len: Float,
    corner: Corner,
): Path {
    val path = Path()
    when (corner) {
        Corner.TopLeft -> {
            path.moveTo(cornerX + r + len, cornerY)
            path.lineTo(cornerX + r, cornerY)
            path.arcTo(
                rect = Rect(Offset(cornerX, cornerY), Size(2f * r, 2f * r)),
                startAngleDegrees = 270f,
                sweepAngleDegrees = -90f,
                forceMoveTo = false,
            )
            path.lineTo(cornerX, cornerY + r + len)
        }

        Corner.TopRight -> {
            path.moveTo(cornerX - r - len, cornerY)
            path.lineTo(cornerX - r, cornerY)
            path.arcTo(
                rect = Rect(Offset(cornerX - 2f * r, cornerY), Size(2f * r, 2f * r)),
                startAngleDegrees = 270f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false,
            )
            path.lineTo(cornerX, cornerY + r + len)
        }

        Corner.BottomRight -> {
            path.moveTo(cornerX, cornerY - r - len)
            path.lineTo(cornerX, cornerY - r)
            path.arcTo(
                rect = Rect(Offset(cornerX - 2f * r, cornerY - 2f * r), Size(2f * r, 2f * r)),
                startAngleDegrees = 0f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false,
            )
            path.lineTo(cornerX - r - len, cornerY)
        }

        Corner.BottomLeft -> {
            path.moveTo(cornerX + r + len, cornerY)
            path.lineTo(cornerX + r, cornerY)
            path.arcTo(
                rect = Rect(Offset(cornerX, cornerY - 2f * r), Size(2f * r, 2f * r)),
                startAngleDegrees = 90f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false,
            )
            path.lineTo(cornerX, cornerY - r - len)
        }
    }
    return path
}
