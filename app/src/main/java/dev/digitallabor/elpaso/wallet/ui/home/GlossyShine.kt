package dev.digitallabor.elpaso.wallet.ui.home

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlin.math.cos
import kotlin.math.sin

/**
 * Device tilt expressed as a roughly unit Offset, `x` = roll (rotate the phone
 * around its long axis), `y` = pitch (tip forward/back). Zero is the resting
 * hand pose. The default value here is what cards animate to when the device
 * is flat on a table or the sensor is unavailable — they still shimmer via the
 * idle drift baked into [glossyShine].
 *
 * Provided once per screen by [DeviceTiltProvider] so every visible card shares
 * a single sensor subscription instead of each opening its own channel.
 */
val LocalCardTilt = compositionLocalOf<State<Offset>> { mutableStateOf(Offset.Zero) }

/**
 * Wraps [content] in a [CompositionLocalProvider] supplying the live device
 * tilt to [LocalCardTilt]. One [DeviceTiltProvider] per visible screen, please —
 * stacking them stacks sensor listeners for no benefit.
 */
@Composable
fun DeviceTiltProvider(content: @Composable () -> Unit) {
    val tilt = rememberDeviceTilt()
    androidx.compose.runtime.CompositionLocalProvider(LocalCardTilt provides tilt) {
        content()
    }
}

/**
 * Subscribes to `TYPE_GAME_ROTATION_VECTOR` (or `TYPE_ROTATION_VECTOR` as a
 * fallback) and returns a smoothed tilt state. Game-rotation-vector skips the
 * compass — drift over time doesn't matter for a sheen, and we avoid the
 * magnetic-interference jumps that the regular rotation vector picks up around
 * laptops / speakers / metal furniture.
 */
@Composable
fun rememberDeviceTilt(): State<Offset> {
    val context = LocalContext.current
    val tilt = remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(context) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sm == null || sensor == null) {
            // Some emulators / very old devices have neither rotation sensor.
            // The cards still animate via the idle drift, just not the tilt.
            return@DisposableEffect onDispose { }
        }
        val listener = object : SensorEventListener {
            // Low-pass smoothing carried across events so sensor noise doesn't
            // make the shine vibrate at rest.
            private val smoothed = floatArrayOf(0f, 0f)
            override fun onSensorChanged(event: SensorEvent) {
                val rotation = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotation, orientation)
                // orientation[1] = pitch (-PI/2..PI/2), orientation[2] = roll (-PI..PI).
                // Subtract REST_PITCH so "hand at natural reading angle" reads as
                // zero — otherwise the shine sits permanently off-centre.
                val pitchNorm = ((orientation[1] + REST_PITCH) / TILT_RANGE_RAD).coerceIn(-1f, 1f)
                val rollNorm = (orientation[2] / TILT_RANGE_RAD).coerceIn(-1f, 1f)
                smoothed[0] = smoothed[0] * (1f - SMOOTHING) + rollNorm * SMOOTHING
                smoothed[1] = smoothed[1] * (1f - SMOOTHING) + pitchNorm * SMOOTHING
                tilt.value = Offset(smoothed[0], smoothed[1])
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }
    return tilt
}

/**
 * Glossy moving highlight that replaces [dev.digitallabor.elpaso.wallet.domain.model.PassArt.sheenOverlay]
 * for the home deck. A soft diagonal white stripe sweeps across the card; its
 * position is driven by [LocalCardTilt] plus a slow looping drift so the cards
 * have life even when the user holds the phone perfectly still.
 *
 * Apply in place of `.background(brush = art.sheenOverlay)` — the stripe draws
 * on top of whatever earlier modifiers painted (e.g. the base gradient) and
 * below the composable's own children.
 */
fun Modifier.glossyShine(): Modifier = composed {
    val tilt by LocalCardTilt.current
    // 8-second linear sweep, mirrored — slow enough not to distract, fast
    // enough to feel alive on a fresh-launched home screen.
    val transition = rememberInfiniteTransition(label = "glossyShineIdle")
    val idleX by transition.animateFloat(
        initialValue = -0.6f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glossyShineIdleX",
    )
    drawBehind {
        val w = size.width
        val h = size.height
        // Combine tilt (dominant) with the idle drift (half-weight). Cap a bit
        // wider than [-1, 1] so the stripe can fully exit the card on extreme
        // tilts rather than parking at the edge.
        val offsetX = (tilt.x + idleX * 0.5f).coerceIn(-1.2f, 1.2f)
        val offsetY = tilt.y.coerceIn(-1f, 1f)
        // Diagonal stripe angle — -20° gives the classic credit-card swipe look.
        val angle = STRIPE_ANGLE_RAD
        val dirX = cos(angle)
        val dirY = sin(angle)
        // Perpendicular = direction the stripe moves as offset grows.
        val perpX = -dirY
        val perpY = dirX
        val shiftMag = (offsetX * w * 0.55f)
        val pitchShift = (offsetY * h * 0.30f)
        val cx = w * 0.5f + perpX * shiftMag + dirX * pitchShift
        val cy = h * 0.5f + perpY * shiftMag + dirY * pitchShift
        // Stripe reach: long enough that the bright band has time to fade to
        // transparent before the card edge cuts it off, even at full tilt.
        val stripeReach = w.coerceAtMost(h) * 0.65f
        val start = Offset(cx - perpX * stripeReach, cy - perpY * stripeReach)
        val end = Offset(cx + perpX * stripeReach, cy + perpY * stripeReach)
        val brush = Brush.linearGradient(
            colorStops = SHINE_STOPS,
            start = start,
            end = end,
        )
        drawRect(brush = brush)
    }
}

private const val SMOOTHING = 0.18f
// "Phone held upright at ~30° tilt forward" is the natural reading pose.
// Subtracting this bias lets the shine sit centred at rest rather than
// permanently parked at the top of the card.
private const val REST_PITCH = 0.5f
// Tilt ±~30° from rest maps to the shine reaching the card's far edge.
private val TILT_RANGE_RAD = (Math.PI / 6.0).toFloat()
private val STRIPE_ANGLE_RAD = (-20.0 * Math.PI / 180.0).toFloat()
private val SHINE_STOPS: Array<Pair<Float, Color>> = arrayOf(
    0f to Color.Transparent,
    0.40f to Color.White.copy(alpha = 0.08f),
    0.50f to Color.White.copy(alpha = 0.32f),
    0.60f to Color.White.copy(alpha = 0.08f),
    1f to Color.Transparent,
)
