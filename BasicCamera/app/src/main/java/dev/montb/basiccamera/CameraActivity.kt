package dev.montb.basiccamera

import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import dev.montb.basiccamera.ui.CameraScreen

/**
 * The whole app: one full-screen camera. It handles four entry points,
 *  - launched normally / via the hardware camera button (STILL_IMAGE_CAMERA),
 *  - asked for a photo by another app (ACTION_IMAGE_CAPTURE, optional EXTRA_OUTPUT),
 *  - asked to start in video (VIDEO_CAMERA / VIDEO_CAPTURE),
 *  - launched over the lock screen (the *_SECURE actions), see `isSecure` below.
 */
class CameraActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Preview fills the screen, behind the system bars; keep the screen awake so it
        // never dims mid-shot.
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val action = intent?.action
        val isCaptureIntent = action == MediaStore.ACTION_IMAGE_CAPTURE ||
            action == MediaStore.ACTION_IMAGE_CAPTURE_SECURE
        val startInVideo = action == MediaStore.ACTION_VIDEO_CAPTURE ||
            action == MediaStore.INTENT_ACTION_VIDEO_CAMERA
        // Secure launch = fired from the lock screen. We show over the keyguard and wake
        // the screen, but deliberately do NOT dismiss the lock, so the rest of the phone
        // stays protected (the camera UI also hides existing gallery shots in this mode).
        val isSecure = action == MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE ||
            action == MediaStore.ACTION_IMAGE_CAPTURE_SECURE
        val captureOutputUri: Uri? =
            IntentCompat.getParcelableExtra(intent, MediaStore.EXTRA_OUTPUT, Uri::class.java)

        if (isSecure) {
            setShowWhenLocked(true)  // appear above the keyguard
            setTurnScreenOn(true)    // wake the display when launched
        }

        setContent {
            CameraScreen(
                isCaptureIntent = isCaptureIntent,
                startInVideo = startInVideo,
                captureOutputUri = captureOutputUri,
                secure = isSecure,
            )
        }
    }

    /**
     * The camera screen registers a shutter action here while it's on screen. When set, the
     * volume keys fire it (take a photo, or start/stop video) instead of changing the volume,
     * a one-handed shutter. It's cleared when the screen leaves, so volume works normally
     * everywhere else.
     */
    var onShutterKey: (() -> Unit)? = null

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (onShutterKey != null && isVolumeKey(keyCode)) {
            if (event.repeatCount == 0) onShutterKey?.invoke()  // ignore auto-repeat while held
            return true                                          // consume → volume doesn't change
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Swallow the matching key-up too, so the system volume panel never flashes.
        if (onShutterKey != null && isVolumeKey(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun isVolumeKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
}
