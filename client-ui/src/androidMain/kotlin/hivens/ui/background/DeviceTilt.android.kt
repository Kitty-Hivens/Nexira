package hivens.ui.background

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/**
 * How far the phone is tilted from the way it is being held, as the parallax's pointer offset:
 * zero at rest, within -0.5..0.5 on each axis.
 *
 * Measured against a slow running average of gravity instead of against level, so the picture
 * moves when the phone moves and settles back when it is held still, whatever angle it is held at.
 * The sensor is listened to only while [enabled] and the screen is in front, and nothing it reads
 * leaves this class.
 */
@Composable
internal fun rememberDeviceTilt(enabled: Boolean): () -> Offset {
    val context = LocalContext.current
    var tilt by remember { mutableStateOf(Offset.Zero) }
    val owner = context as? LifecycleOwner

    DisposableEffect(enabled, owner) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (!enabled || sensor == null) {
            tilt = Offset.Zero
            return@DisposableEffect onDispose { }
        }
        val listener = TiltListener(context) { tilt = it }
        val start = { manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI) }
        val stop = {
            manager.unregisterListener(listener)
            listener.reset()
            tilt = Offset.Zero
        }
        val lifecycle = owner?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> start()
                Lifecycle.Event.ON_PAUSE -> stop()
                else -> Unit
            }
        }
        if (lifecycle == null) {
            start()
        } else {
            lifecycle.addObserver(observer)
        }
        onDispose {
            lifecycle?.removeObserver(observer)
            stop()
        }
    }
    return { tilt }
}

private class TiltListener(
    private val context: Context,
    private val onTilt: (Offset) -> Unit,
) : SensorEventListener {

    private var baseX = 0f
    private var baseY = 0f
    private var seeded = false

    fun reset() {
        seeded = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        // The sensor's axes belong to the phone, the picture's to the screen, and the two part
        // company when the screen turns.
        val (x, y) = when (context.display.rotation) {
            Surface.ROTATION_90 -> -event.values[1] to event.values[0]
            Surface.ROTATION_180 -> -event.values[0] to -event.values[1]
            Surface.ROTATION_270 -> event.values[1] to -event.values[0]
            else -> event.values[0] to event.values[1]
        }
        if (!seeded) {
            baseX = x
            baseY = y
            seeded = true
        }
        baseX += (x - baseX) * FOLLOW
        baseY += (y - baseY) * FOLLOW
        onTilt(Offset(axis(x - baseX), axis(y - baseY)))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** A swing of [FULL_SWING] m/s2 (about 17 degrees) takes the picture to the end of its travel. */
    private fun axis(delta: Float): Float = (delta / FULL_SWING * 0.5f).coerceIn(-0.5f, 0.5f)

    private companion object {
        const val FOLLOW = 0.02f
        const val FULL_SWING = 3f
    }
}
