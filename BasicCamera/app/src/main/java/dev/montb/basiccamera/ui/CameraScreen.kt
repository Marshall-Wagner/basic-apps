package dev.montb.basiccamera.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.hardware.SensorManager
import android.net.Uri
import android.os.SystemClock
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.OrientationEventListener
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.montb.basiccamera.CameraActivity
import dev.montb.basiccamera.camera.CameraGestures
import dev.montb.basiccamera.camera.Lenses
import dev.montb.basiccamera.media.CaptureStore
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Top-level screen: ask for camera (and mic) permission, then show the live camera.
 */
@Composable
fun CameraScreen(
    isCaptureIntent: Boolean,
    startInVideo: Boolean,
    captureOutputUri: Uri?,
    secure: Boolean = false,
) {
    val context = LocalContext.current
    var hasCamera by remember { mutableStateOf(context.granted(Manifest.permission.CAMERA)) }
    var hasAudio by remember { mutableStateOf(context.granted(Manifest.permission.RECORD_AUDIO)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasCamera = result[Manifest.permission.CAMERA] ?: hasCamera
        hasAudio = result[Manifest.permission.RECORD_AUDIO] ?: hasAudio
    }

    LaunchedEffect(Unit) {
        if (!hasCamera || !hasAudio) {
            launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    if (!hasCamera) {
        PermissionGate {
            launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
        return
    }

    CameraContent(
        isCaptureIntent = isCaptureIntent,
        startInVideo = startInVideo,
        captureOutputUri = captureOutputUri,
        audioGranted = hasAudio,
        secure = secure,
    )
}

@SuppressLint("MissingPermission") // RECORD_AUDIO is gated by `audioGranted` below.
@Composable
private fun CameraContent(
    isCaptureIntent: Boolean,
    startInVideo: Boolean,
    captureOutputUri: Uri?,
    audioGranted: Boolean,
    secure: Boolean,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val executor = remember { ContextCompat.getMainExecutor(context) }

    val controller = remember {
        LifecycleCameraController(context).apply {
            // Focus / exposure / zoom are driven by CameraGestures (see below) so we can
            // show a reticle and a drag-to-brighten control; disable the bare built-ins.
            // Favor quality over shutter speed so the Qualcomm ISP applies its best
            // processing (multi-frame noise reduction etc.) to each still.
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
            bindToLifecycle(lifecycleOwner)
        }
    }

    // Tap-to-meter reticle position (view px) and a transient pinch-zoom readout ("2.5×").
    var focusRing by remember { mutableStateOf<Offset?>(null) }
    var zoomLabel by remember { mutableStateOf<String?>(null) }
    val gestures = remember {
        CameraGestures(
            controller = controller,
            onFocus = { x, y -> focusRing = Offset(x, y) },
            onZoom = { ratio -> zoomLabel = formatZoomLabel(ratio) },
        )
    }

    // Auto-hide the reticle / zoom readout shortly after the gesture ends.
    LaunchedEffect(focusRing) { if (focusRing != null) { delay(900); focusRing = null } }
    LaunchedEffect(zoomLabel) { if (zoomLabel != null) { delay(1200); zoomLabel = null } }

    var backCamera by remember { mutableStateOf(true) }
    var videoMode by remember { mutableStateOf(startInVideo) }
    // Still-photo aspect ratio. Defaults to 4:3 (the sensor's native, full-detail frame).
    var photoAspect by remember { mutableStateOf(PhotoAspect.RATIO_4_3) }
    // "High efficiency": save our own shots as HEIC (~half the size of JPEG). On by default
    // (user optimizes for storage); capture-intent shots stay JPEG regardless (see capturePhoto).
    var heicEnabled by remember { mutableStateOf(true) }
    // Captured photo resolution. 12 MP = the sensor's full binned output; "Max" tries the
    // sensor's highest (the 50 MP high-res mode if the ROM exposes it, else ~12.5 MP).
    var photoResolution by remember { mutableStateOf(PhotoResolution.MP12) }
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_AUTO) }
    var torchOn by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var capturing by remember { mutableStateOf(false) }
    var lastThumb by remember { mutableStateOf<Bitmap?>(null) }
    // Rear lenses the ROM exposes (discovered at startup); starts as just the default cam.
    var backLenses by remember { mutableStateOf(Lenses.DEFAULT_BACK) }
    // Zoom buttons: each discovered rear lens at 1×, plus a "2×" digital crop of the main.
    val zoomPresets = remember(backLenses) { ZoomPreset.build(backLenses) }
    var selectedZoom by remember(zoomPresets) {
        mutableIntStateOf(zoomPresets.indexOfFirst { it.label == "1×" }.coerceAtLeast(0))
    }

    val isRecording = recording != null
    // Spin the controls to match how the phone is physically held (auto-rotate is irrelevant).
    val controlRotation = rememberControlRotation()

    // Discover the real rear lenses once.
    LaunchedEffect(Unit) {
        backLenses = Lenses.backLenses(context)
    }

    // Push the selected lens / facing onto the controller, then apply the preset's zoom. Only
    // "2×" is non-1× (a digital crop of the main); it shares the main lens's selector with "1×",
    // so 1×↔2× is just a zoom change with no rebind.
    LaunchedEffect(backCamera, selectedZoom, zoomPresets) {
        if (!backCamera) {
            controller.cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            return@LaunchedEffect
        }
        val preset = zoomPresets.getOrNull(selectedZoom) ?: return@LaunchedEffect
        val lensChanged = controller.cameraSelector !== preset.selector
        controller.cameraSelector = preset.selector
        if (lensChanged) delay(250)  // let a lens swap rebind before zooming
        controller.cameraControl?.setZoomRatio(preset.zoom)
    }
    LaunchedEffect(videoMode) {
        controller.setEnabledUseCases(
            if (videoMode) CameraController.VIDEO_CAPTURE else CameraController.IMAGE_CAPTURE
        )
    }
    // Apply the chosen aspect ratio + resolution to the captured image. The preview is framed
    // to match below (see previewModifier), so the on-screen crop is what actually gets saved.
    LaunchedEffect(photoAspect, photoResolution) {
        controller.imageCaptureResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(photoAspect.strategy)
            // Permit the sensor's ultra-high-res (50 MP) sizes to be chosen when asked for.
            .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
            .setResolutionStrategy(
                ResolutionStrategy(
                    photoResolution.targetSize(photoAspect),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                )
            )
            .build()
    }
    LaunchedEffect(flashMode) { controller.imageCaptureFlashMode = flashMode }
    LaunchedEffect(torchOn, videoMode) { if (videoMode) controller.enableTorch(torchOn) }

    // Seed the gallery thumbnail with the most recent shot, but NOT in secure mode,
    // where surfacing an earlier private photo on the lock screen would defeat the lock.
    // Secure sessions only ever show thumbnails of shots taken in this session.
    LaunchedEffect(Unit) {
        if (!secure) {
            lastThumb = withContext(Dispatchers.IO) { CaptureStore.latestThumbnail(context) }
        }
    }

    // Recording elapsed-time ticker.
    LaunchedEffect(isRecording) {
        if (isRecording) {
            val start = SystemClock.elapsedRealtime()
            while (true) {
                elapsedMs = SystemClock.elapsedRealtime() - start
                delay(200)
            }
        } else {
            elapsedMs = 0L
        }
    }

    fun refreshThumb(uri: Uri?) {
        if (uri == null) return
        scope.launch {
            withContext(Dispatchers.IO) { CaptureStore.thumbnail(context, uri) }?.let { lastThumb = it }
        }
    }

    fun capturePhoto() {
        if (capturing) return
        capturing = true
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

        // Our own shots are captured in-memory and re-encoded by us, off the main thread, so
        // they carry near-zero metadata (no location, timestamp, camera model, or exposure),
        // HEIC, or an EXIF-free re-encoded JPEG. A capture-intent shot instead falls through to
        // the standard path below, delivering a normal JPEG (with EXIF) as the caller expects.
        if (!isCaptureIntent) {
            controller.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rotation = image.imageInfo.rotationDegrees
                    val bytes = image.toJpegBytes()
                    image.close()
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            if (heicEnabled) CaptureStore.saveHeic(context, bytes, rotation)
                            else CaptureStore.saveStrippedJpeg(context, bytes, rotation)
                        }
                        capturing = false
                        if (saved != null) refreshThumb(saved)
                        else context.toast("Couldn't save photo")
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                    context.toast("Couldn't capture photo: ${exception.message}")
                }
            })
            return
        }

        val options = if (captureOutputUri != null) {
            CaptureStore.streamOutput(context.contentResolver, captureOutputUri)
        } else {
            CaptureStore.imageOutput(context.contentResolver)
        }
        controller.takePicture(options, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                capturing = false
                val saved = results.savedUri ?: captureOutputUri
                if (isCaptureIntent) {
                    val act = context.findActivity() ?: return
                    val data = if (captureOutputUri == null && saved != null) {
                        Intent().setData(saved)
                    } else null
                    act.setResult(Activity.RESULT_OK, data)
                    act.finish()
                } else {
                    refreshThumb(saved)
                }
            }

            override fun onError(exception: ImageCaptureException) {
                capturing = false
                context.toast("Couldn't save photo: ${exception.message}")
            }
        })
    }

    fun toggleRecording() {
        val current = recording
        if (current != null) {
            current.stop()
            recording = null
            return
        }
        val options = CaptureStore.videoOutput(context.contentResolver)
        val audio = if (audioGranted) AudioConfig.create(true) else AudioConfig.AUDIO_DISABLED
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        recording = controller.startRecording(options, audio, executor) { event ->
            if (event is VideoRecordEvent.Finalize) {
                if (event.hasError()) {
                    context.toast("Recording failed (code ${event.error})")
                } else {
                    refreshThumb(event.outputResults.outputUri)
                }
                recording = null
            }
        }
    }

    // Volume-rocker shutter: while this screen is up, either volume key fires the shutter (a
    // photo, or start/stop in video mode) instead of changing the volume. Handled by the host
    // activity (hardware keys arrive there); cleared on dispose so volume works normally elsewhere.
    val shutterActivity = remember(context) { context.findActivity() as? CameraActivity }
    DisposableEffect(shutterActivity) {
        shutterActivity?.onShutterKey = { if (videoMode) toggleRecording() else capturePhoto() }
        onDispose { shutterActivity?.onShutterKey = null }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // In photo mode the preview is letterboxed to the chosen aspect ratio so it's
        // what-you-see-is-what-you-get; video keeps the full-bleed preview. The box fits to
        // width in portrait and to height in landscape, since the device rotates (fullUser).
        val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val previewModifier = when {
            videoMode -> Modifier.fillMaxSize()
            landscape -> Modifier.align(Alignment.Center).fillMaxHeight()
                .aspectRatio(photoAspect.displayRatio(landscape = true))
            else -> Modifier.align(Alignment.Center).fillMaxWidth()
                .aspectRatio(photoAspect.displayRatio(landscape = false))
        }
        // Preview + reticle share one box so the tap coordinates (relative to the framed
        // PreviewView) line up with where the reticle is drawn, important now that the
        // preview no longer fills the screen in photo mode.
        Box(previewModifier) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        this.controller = controller
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        gestures.attach(this)
                    }
                }
            )

            // Tap-to-focus reticle.
            focusRing?.let { p ->
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Color.White, radius = 38.dp.toPx(), center = p, style = Stroke(2.dp.toPx()))
                    drawCircle(Color.White, radius = 3.dp.toPx(), center = p)
                }
            }
        }

        // Pinch-zoom level readout.
        zoomLabel?.let { label ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x88000000))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    label,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.rotate(controlRotation),
                )
            }
        }

        // --- top bar: flash / torch + camera switch ---
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (videoMode) {
                    OverlayIcon(
                        icon = if (torchOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                        desc = "Torch",
                        rotation = controlRotation,
                        onClick = { torchOn = !torchOn },
                    )
                } else {
                    val (icon, desc) = when (flashMode) {
                        ImageCapture.FLASH_MODE_ON -> Icons.Filled.FlashOn to "Flash on"
                        ImageCapture.FLASH_MODE_OFF -> Icons.Filled.FlashOff to "Flash off"
                        else -> Icons.Filled.FlashAuto to "Flash auto"
                    }
                    OverlayIcon(icon = icon, desc = desc, rotation = controlRotation, onClick = {
                        flashMode = when (flashMode) {
                            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
                            else -> ImageCapture.FLASH_MODE_AUTO
                        }
                    })
                    // Stills-only controls. Format is hidden for capture-intent shots, which
                    // are always JPEG for the requesting app.
                    TopBarChip(label = photoAspect.label, rotation = controlRotation) {
                        photoAspect = photoAspect.next()
                    }
                    TopBarChip(label = photoResolution.label, rotation = controlRotation) {
                        photoResolution = photoResolution.next()
                    }
                    if (!isCaptureIntent) {
                        TopBarChip(
                            label = if (heicEnabled) "HEIC" else "JPEG",
                            active = heicEnabled,
                            rotation = controlRotation,
                        ) { heicEnabled = !heicEnabled }
                    }
                }
            }

            if (isRecording) {
                RecordTimer(elapsedMs, controlRotation)
            }

            OverlayIcon(
                icon = Icons.Filled.Cameraswitch,
                desc = "Switch camera",
                enabled = !isRecording,
                rotation = controlRotation,
                onClick = { backCamera = !backCamera },
            )
        }

        // --- bottom area: an optional lens picker above the controls row ---
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Only when on the rear camera AND the ROM exposed more than one rear lens.
            if (backCamera && zoomPresets.size > 1) {
                LensPicker(
                    labels = zoomPresets.map { it.label },
                    selected = selectedZoom,
                    enabled = !isRecording,
                    rotation = controlRotation,
                    onSelect = { selectedZoom = it },
                )
                Spacer(Modifier.height(14.dp))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
            ThumbnailButton(lastThumb, controlRotation) {
                // Opening the gallery over a locked phone would expose every photo, so in
                // secure mode the thumbnail is review-only feedback, not a gallery shortcut.
                if (secure) {
                    context.toast("Unlock the phone to view your photos")
                    return@ThumbnailButton
                }
                val uri = CaptureStore.latestImageUri(context)
                if (uri != null) {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, uri)
                                .setDataAndType(uri, "image/*")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        )
                    }
                }
            }

            ShutterButton(
                videoMode = videoMode,
                recording = isRecording,
                enabled = !capturing,
            ) {
                if (videoMode) toggleRecording() else capturePhoto()
            }

            OverlayIcon(
                icon = if (videoMode) Icons.Filled.PhotoCamera else Icons.Filled.Videocam,
                desc = if (videoMode) "Switch to photo" else "Switch to video",
                enabled = !isRecording,
                rotation = controlRotation,
                onClick = {
                    torchOn = false
                    videoMode = !videoMode
                },
            )
            }
        }
    }
}

@Composable
private fun PermissionGate(onGrant: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Camera permission needed",
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Basic Camera needs the camera (and the microphone for video) to work. " +
                "Saved photos go to your gallery only — this app has no internet permission.",
            color = Color(0xFFBDBDBD),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrant) { Text("Grant camera access") }
    }
}

@Composable
private fun RecordTimer(elapsedMs: Long, rotation: Float = 0f) {
    val totalSec = elapsedMs / 1000
    val text = "%02d:%02d".format(totalSec / 60, totalSec % 60)
    Row(modifier = Modifier.rotate(rotation), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFE53935)))
        Spacer(Modifier.size(6.dp))
        Text(text, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 16.sp)
    }
}

/**
 * Tracks the phone's physical orientation via the accelerometer, so it works even with the
 * system auto-rotate lock OFF, and returns the angle to counter-rotate the on-screen controls
 * so they stay upright in your hand. That's the cue you're shooting in landscape, since the
 * activity itself stays portrait. Animated along the shortest path.
 */
@Composable
private fun rememberControlRotation(): Float {
    val context = LocalContext.current
    // Physical device rotation, snapped to 0/90/180/270.
    var device by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context, SensorManager.SENSOR_DELAY_UI) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                device = when {
                    orientation >= 315 || orientation < 45 -> 0
                    orientation < 135 -> 90
                    orientation < 225 -> 180
                    else -> 270
                }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
    // Counter-rotate the UI; accumulate continuously so the spin always takes the short way.
    var continuous by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(device) {
        val target = -device.toFloat()
        continuous += ((target - continuous) % 360f + 540f) % 360f - 180f
    }
    val angle by animateFloatAsState(continuous, label = "controlRotation")
    return angle
}

@Composable
private fun OverlayIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    enabled: Boolean = true,
    rotation: Float = 0f,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = if (enabled) Color.White else Color(0x66FFFFFF),
            modifier = Modifier.size(28.dp).rotate(rotation),
        )
    }
}

@Composable
private fun ThumbnailButton(thumb: Bitmap?, rotation: Float = 0f, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x33FFFFFF))
            .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (thumb != null) {
            Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = "Open last shot",
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
            )
        } else {
            Icon(
                Icons.Filled.PhotoLibrary,
                contentDescription = "Gallery",
                tint = Color.White,
                modifier = Modifier.size(26.dp).rotate(rotation),
            )
        }
    }
}

@Composable
private fun ShutterButton(
    videoMode: Boolean,
    recording: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val ring = if (enabled) Color.White else Color(0x66FFFFFF)
    Box(
        modifier = Modifier
            .size(78.dp)
            .clip(CircleShape)
            .border(4.dp, ring, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            videoMode && recording -> Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFE53935))
            )
            videoMode -> Box(Modifier.size(58.dp).clip(CircleShape).background(Color(0xFFE53935)))
            else -> Box(Modifier.size(62.dp).clip(CircleShape).background(Color.White))
        }
    }
}

@Composable
private fun LensPicker(
    labels: List<String>,
    selected: Int,
    enabled: Boolean,
    rotation: Float = 0f,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0x66000000))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { i, label ->
            val sel = i == selected
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (sel) Color.White else Color(0x33FFFFFF))
                    .clickable(enabled = enabled) { onSelect(i) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (sel) Color.Black else Color.White,
                    fontSize = 13.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.rotate(rotation),
                )
            }
        }
    }
}

/**
 * A button in the lens picker: a physical lens at 1×, or a digital-zoom crop of the main. The
 * main lens's "1×" and "2×" presets share one [selector], so switching between them is a pure
 * zoom change (no camera rebind). [order] is the display zoom, used only to sort widest→tightest.
 */
private data class ZoomPreset(
    val label: String,
    val selector: CameraSelector,
    val zoom: Float,
    val order: Float,
) {
    companion object {
        fun build(lenses: List<Lenses.BackLens>): List<ZoomPreset> {
            val main = lenses.firstOrNull { it.label == "1×" } ?: lenses.firstOrNull()
            val presets = lenses.mapTo(mutableListOf()) { lens ->
                ZoomPreset(lens.label, lens.selector, 1f, lens.label.removeSuffix("×").toFloatOrNull() ?: 1f)
            }
            if (main != null) presets += ZoomPreset("2×", main.selector, 2f, 2f)
            return presets.sortedBy { it.order }
        }
    }
}

/** A small tappable text pill in the top bar (aspect ratio, output format, …). */
@Composable
private fun TopBarChip(label: String, active: Boolean = true, rotation: Float = 0f, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(start = 4.dp)
            .rotate(rotation)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) Color(0x33FFFFFF) else Color(0x1FFFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (active) Color.White else Color(0xB3FFFFFF),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Still-photo aspect ratio the user can toggle. 4:3 is the sensor's native, full-detail
 * frame (the default); 16:9 is a wider crop. [strategy] sets the captured image, while
 * [displayRatio] frames the preview box so what's on screen is what gets saved.
 */
private enum class PhotoAspect(
    val label: String,
    private val wide: Int,
    private val tall: Int,
    val strategy: AspectRatioStrategy,
) {
    RATIO_4_3("4:3", 4, 3, AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY),
    RATIO_16_9("16:9", 16, 9, AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY);

    fun next(): PhotoAspect = if (this == RATIO_4_3) RATIO_16_9 else RATIO_4_3

    /** Preview-box width/height for the current device orientation. */
    fun displayRatio(landscape: Boolean): Float =
        if (landscape) wide.toFloat() / tall else tall.toFloat() / wide
}

/**
 * Captured-photo resolution the user can cycle. Values are megapixel goals; CameraX snaps to
 * the nearest size the sensor actually offers for the chosen aspect ratio. "Max" aims past the
 * binned ceiling so it grabs the 50 MP high-res mode *if* the ROM exposes it, otherwise it
 * lands on the sensor's full binned output (~12.5 MP), same as 12 MP.
 */
private enum class PhotoResolution(val label: String, private val megapixels: Double) {
    MAX("Max", 60.0),
    MP12("12 MP", 12.0),
    MP8("8 MP", 8.0),
    MP5("5 MP", 5.0),
    MP3("3 MP", 3.0);

    fun next(): PhotoResolution = entries[(ordinal + 1) % entries.size]

    /** Target capture size for [aspect] derived from the megapixel goal (CameraX snaps to nearest). */
    fun targetSize(aspect: PhotoAspect): Size {
        val ratio = aspect.displayRatio(landscape = true).toDouble() // wide / tall
        val height = sqrt(megapixels * 1_000_000.0 / ratio)
        return Size((height * ratio).roundToInt(), height.roundToInt())
    }
}

// --- small helpers ---

/** Formats a zoom ratio for the readout: "1×", "2.5×", "8×". */
private fun formatZoomLabel(ratio: Float): String =
    if (kotlin.math.abs(ratio - ratio.roundToInt()) < 0.05f) "${ratio.roundToInt()}×"
    else "%.1f×".format(ratio)

/** Copies the JPEG bytes out of an in-memory [ImageProxy] (ImageCapture's default format). */
private fun ImageProxy.toJpegBytes(): ByteArray {
    val buffer = planes[0].buffer
    return ByteArray(buffer.remaining()).also { buffer.get(it) }
}

private fun Context.granted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.toast(msg: String) =
    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
