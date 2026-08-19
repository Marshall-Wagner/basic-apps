package dev.montb.basiccamera.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Works out which rear lenses the ROM actually exposes to third-party apps and builds a
 * CameraX selector for each, with a human zoom label ("0.6×", "1×", "2×"). Many OEMs hide
 * the ultrawide/macro lenses from anything but their own camera app, in that case the
 * enumeration only finds the main lens and the picker collapses to a single entry. So this
 * is also how we *discover* what your phone allows, not just how we switch.
 */
object Lenses {

    data class BackLens(val label: String, val selector: CameraSelector, val cameraId: String?)

    /** Fallback when enumeration isn't possible: just the default rear camera, as "1×". */
    val DEFAULT_BACK = listOf(BackLens("1×", CameraSelector.DEFAULT_BACK_CAMERA, null))

    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    suspend fun backLenses(context: Context): List<BackLens> = withContext(Dispatchers.IO) {
        runCatching {
            val provider = ProcessCameraProvider.getInstance(context).get()
            val backInfos = provider.availableCameraInfos.filter {
                it.lensFacing == CameraSelector.LENS_FACING_BACK
            }
            if (backInfos.size <= 1) return@runCatching DEFAULT_BACK

            // NOTE: this ROM reports bogus SENSOR_INFO_PHYSICAL_SIZE, so the 35mm-equiv math
            // labels the lenses BACKWARDS, verified on-device, it called the 13 MP main
            // "0.7×" and a 5 MP wide camera "1×" (which is why "1×" shots came out 5 MP). So we
            // identify the MAIN reliably as the highest-resolution rear camera; and because the
            // reported equiv is reciprocal to the true field of view here, the *other* lenses'
            // zoom is the INVERTED equiv ratio (mainEquiv / lensEquiv), not the textbook one.
            data class Raw(val id: String, val equiv: Float?, val area: Long)
            val raws = backInfos.map { info ->
                val c2 = Camera2CameraInfo.from(info)
                val focal = c2.getCameraCharacteristic(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                )?.firstOrNull()
                val size = c2.getCameraCharacteristic(
                    CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
                )
                val equiv = if (focal != null && size != null && size.width > 0f) {
                    focal * 36f / size.width   // 36mm = full-frame sensor width
                } else null
                val maxArea = c2.getCameraCharacteristic(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                )?.getOutputSizes(ImageFormat.JPEG)
                    ?.maxOfOrNull { it.width.toLong() * it.height } ?: 0L
                Raw(c2.cameraId, equiv, maxArea)
            }

            // Main = the highest-resolution rear camera; its equiv anchors the others' labels.
            val main = raws.maxByOrNull { it.area }
            val mainEquiv = main?.equiv

            // Main (1×) first, then the rest by resolution (descending).
            val sorted = raws.sortedByDescending { it.area }
            val lenses = sorted.map { raw ->
                val zoom = when {
                    raw.id == main?.id -> 1f
                    raw.equiv != null && mainEquiv != null && raw.equiv > 0f -> mainEquiv / raw.equiv
                    else -> null
                }
                BackLens(
                    label = zoom?.let(::formatZoom) ?: "Cam",
                    selector = selectorForId(raw.id),
                    cameraId = raw.id,
                )
            }
            dedupeLabels(lenses)
        }.getOrDefault(DEFAULT_BACK)
    }

    /** A selector pinned to one physical camera id (how we pick a specific lens). */
    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun selectorForId(cameraId: String): CameraSelector =
        CameraSelector.Builder()
            .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == cameraId } }
            .build()

    // Common marketing zoom factors. We snap a computed ratio to the nearest of these when
    // it's within ~12%, so the ROG's ultrawide reads the familiar "0.6×" rather than a
    // literal "0.7×" derived from its exact focal length (≈0.66×).
    private val STANDARD_ZOOMS = floatArrayOf(0.5f, 0.6f, 1f, 2f, 3f, 5f, 10f)

    private fun formatZoom(z: Float): String {
        val nearest = STANDARD_ZOOMS.minByOrNull { abs(it - z) }
        val v = if (nearest != null && abs(nearest - z) / nearest <= 0.12f) nearest
                else (z * 10f).roundToInt() / 10f
        return if (abs(v - v.roundToInt()) < 0.05f) "${v.roundToInt()}×" else "%.1f×".format(v)
    }

    /** Keep labels distinct so two ~1× lenses (e.g. main + macro) aren't indistinguishable. */
    private fun dedupeLabels(lenses: List<BackLens>): List<BackLens> {
        val seen = HashMap<String, Int>()
        return lenses.map { lens ->
            val n = (seen[lens.label] ?: 0) + 1
            seen[lens.label] = n
            if (n == 1) lens else lens.copy(label = "${lens.label} ($n)")
        }
    }
}
