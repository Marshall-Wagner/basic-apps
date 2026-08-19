package dev.montb.basiccamera.camera

import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView

/**
 * Custom preview gestures, tuned for this ROM's locked-down third-party camera API:
 *   - tap → meter exposure at that point; [onFocus] reports the spot so the UI can flash a
 *     reticle. The lenses are fixed-focus and the ROM blocks AF for third-party apps, so a tap
 *     can't move focus, but the camera honors a single AE metering region, so tapping your
 *     subject sets the exposure for it.
 *   - pinch → zoom.
 *
 * Manual brightness was removed: this ROM exposes neither working exposure-compensation nor
 * MANUAL_SENSOR to sideloaded apps, so there was no real brightness control to offer.
 */
class CameraGestures(
    private val controller: LifecycleCameraController,
    private val onFocus: (Float, Float) -> Unit,
    private val onZoom: (Float) -> Unit,
) {
    // Tracked locally across a pinch so each event accumulates off the previous *requested*
    // zoom, not the lagging applied value. Reading the applied zoomState every event (it
    // updates async, after setZoomRatio) made the old pinch feel sluggish and stall before
    // reaching the camera's max.
    private var zoomRatio = 1f

    @SuppressLint("ClickableViewAccessibility")
    fun attach(preview: PreviewView) {
        controller.isTapToFocusEnabled = false
        controller.isPinchToZoomEnabled = false

        val scale = ScaleGestureDetector(
            preview.context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                    // Continue from wherever the zoom is now (incl. a 1×/2× preset).
                    zoomRatio = controller.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                    onZoom(zoomRatio)
                    return true
                }

                override fun onScale(d: ScaleGestureDetector): Boolean {
                    // Honor the camera's live zoom range. This ROM hard-caps third-party zoom at
                    // the lens's max (4× on the main); the stock app's 8× is its own software
                    // upscaling, which we don't replicate.
                    val zs = controller.cameraInfo?.zoomState?.value
                    val min = zs?.minZoomRatio ?: 1f
                    val max = zs?.maxZoomRatio ?: 1f
                    zoomRatio = (zoomRatio * d.scaleFactor).coerceIn(min, max)
                    controller.cameraControl?.setZoomRatio(zoomRatio)
                    onZoom(zoomRatio)
                    return true
                }
            }
        )

        val tap = GestureDetector(
            preview.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    val point = preview.meteringPointFactory.createPoint(e.x, e.y)
                    controller.cameraControl?.startFocusAndMetering(
                        FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AE).build()
                    )
                    onFocus(e.x, e.y)
                    return true
                }
            }
        )

        preview.setOnTouchListener { _, event ->
            scale.onTouchEvent(event)
            tap.onTouchEvent(event)
            true
        }
    }
}
