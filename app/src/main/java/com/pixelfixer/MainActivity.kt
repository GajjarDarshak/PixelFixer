@file:OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.pixelfixer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import com.google.common.collect.ImmutableList
import java.io.File
import java.io.FileInputStream
import kotlin.math.max
import kotlin.math.min

// 1. Normalized Watermark Box Coordinates (0.0 to 1.0)
data class NormalizedRect(
    val leftPercent: Float,
    val topPercent: Float,
    val widthPercent: Float,
    val heightPercent: Float
)

// 2. Official Google Media3 Watermark Remover (Zero FFmpeg Dependencies)
object WatermarkProcessor {

    fun getVideoAspectRatio(context: Context, uri: Uri): Float {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: 1080f
            val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: 1920f
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            retriever.release()
            val w = if (rot == 90 || rot == 270) rawH else rawW
            val h = if (rot == 90 || rot == 270) rawW else rawH
            if (h > 0f) (w / h).coerceIn(0.3f, 3.0f) else 9f / 16f
        } catch (e: Exception) {
            9f / 16f
        }
    }

    fun removeWatermark(
        context: Context,
        inputUri: Uri,
        normRect: NormalizedRect,
        onProgress: (Int, String) -> Unit,
        onResult: (Boolean, File?, String) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())

        Thread {
            try {
                mainHandler.post { onProgress(3, "Analyzing video dimensions...") }

                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, inputUri)
                val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
                val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 5000L

                val videoWidth = if (rotation == 90 || rotation == 270) rawHeight else rawWidth
                val videoHeight = if (rotation == 90 || rotation == 270) rawWidth else rawHeight

                // Calculate exact watermark pixel box inside the video frame
                val boxX = (normRect.leftPercent * videoWidth).toInt().coerceIn(2, max(4, videoWidth - 16))
                val boxY = (normRect.topPercent * videoHeight).toInt().coerceIn(2, max(4, videoHeight - 16))
                val boxW = (normRect.widthPercent * videoWidth).toInt().coerceIn(8, max(8, videoWidth - boxX - 2))
                val boxH = (normRect.heightPercent * videoHeight).toInt().coerceIn(8, max(8, videoHeight - boxY - 2))

                // Sample 8 keyframes across the video to build seamless Delogo Inpaint patches
                val numSamples = 8
                val patchFrames = ArrayList<IntArray>(numSamples)

                for (i in 0 until numSamples) {
                    val sampleTimeUs = ((durationMs * 1000L * i) / numSamples).coerceAtLeast(0L)
                    val pct = 5 + ((i + 1) * 20 / numSamples)
                    mainHandler.post {
                        onProgress(pct, "Inpainting background pixels (${i + 1}/$numSamples)...")
                    }

                    val frameBmp = retriever.getFrameAtTime(sampleTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (frameBmp != null) {
                        val scaledFrame = if (frameBmp.width != videoWidth || frameBmp.height != videoHeight) {
                            Bitmap.createScaledBitmap(frameBmp, videoWidth, videoHeight, true)
                        } else {
                            frameBmp
                        }
                        patchFrames.add(createDelogoPatch(scaledFrame, boxX, boxY, boxW, boxH))
                        if (scaledFrame !== frameBmp) scaledFrame.recycle()
                        frameBmp.recycle()
                    }
                }
                retriever.release()

                if (patchFrames.isEmpty()) {
                    val fallbackPatch = IntArray(boxW * boxH) { AndroidColor.argb(255, 24, 24, 24) }
                    patchFrames.add(fallbackPatch)
                }

                // Create a single transparent overlay frame and write the first inpainted patch into it
                val overlayBitmap = Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888)
                var currentPatchIndex = -1

                val dynamicDelogoOverlay = object : BitmapOverlay() {
                    override fun getBitmap(presentationTimeUs: Long): Bitmap {
                        val durationUs = max(1000L, durationMs * 1000L)
                        val idx = ((presentationTimeUs.toDouble() / durationUs.toDouble()) * patchFrames.size)
                            .toInt()
                            .coerceIn(0, patchFrames.size - 1)

                        if (idx != currentPatchIndex) {
                            currentPatchIndex = idx
                            val pixels = patchFrames[idx]
                            overlayBitmap.setPixels(pixels, 0, boxW, boxX, boxY, boxW, boxH)
                        }
                        return overlayBitmap
                    }
                }

                val outputFile = File(
                    context.getExternalFilesDir(null),
                    "PixelFixer_${System.currentTimeMillis()}.mp4"
                )
                if (outputFile.exists()) outputFile.delete()

                // Start Google Media3 Hardware Transformer on the Main Thread
                mainHandler.post {
                    onProgress(28, "Starting hardware video encoder...")

                    val overlayEffect = OverlayEffect(ImmutableList.of<TextureOverlay>(dynamicDelogoOverlay))
                    val effects = Effects(emptyList(), listOf<Effect>(overlayEffect))
                    val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(inputUri))
                        .setEffects(effects)
                        .build()

                    var isFinished = false

                    val transformer = Transformer.Builder(context)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                isFinished = true
                                overlayBitmap.recycle()
                                Thread {
                                    val galleryMsg = saveToPhoneGallery(context, outputFile)
                                    mainHandler.post {
                                        onProgress(100, "100% Complete!")
                                        onResult(true, outputFile, galleryMsg)
                                    }
                                }.start()
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException
                            ) {
                                isFinished = true
                                overlayBitmap.recycle()
                                mainHandler.post {
                                    onResult(
                                        false,
                                        null,
                                        "Encoder Error (${exportException.errorCodeName}): ${exportException.localizedMessage}"
                                    )
                                }
                            }
                        })
                        .build()

                    transformer.start(editedMediaItem, outputFile.absolutePath)

                    // Poll live 0-100% hardware encoding progress every 200ms
                    val progressHolder = ProgressHolder()
                    val progressRunnable = object : Runnable {
                        override fun run() {
                            if (isFinished) return
                            val state = transformer.getProgress(progressHolder)
                            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                                val mappedPct = (28 + (progressHolder.progress * 71 / 100)).coerceIn(28, 99)
                                onProgress(mappedPct, "Removing watermark... $mappedPct%")
                            }
                            if (state != Transformer.PROGRESS_STATE_NOT_STARTED && !isFinished) {
                                mainHandler.postDelayed(this, 200L)
                            }
                        }
                    }
                    mainHandler.postDelayed(progressRunnable, 200L)
                }

            } catch (e: Throwable) {
                mainHandler.post {
                    onResult(false, null, "Error (${e.javaClass.simpleName}): ${e.localizedMessage ?: "Unknown error"}")
                }
            }
        }.start()
    }

    /**
     * True 2D Bilinear Delogo Interpolation:
     * Samples the clean border pixels surrounding the watermark box (top, bottom, left, right)
     * and smoothly interpolates them across the box so the watermark disappears seamlessly.
     */
    private fun createDelogoPatch(frame: Bitmap, boxX: Int, boxY: Int, boxW: Int, boxH: Int): IntArray {
        val patch = IntArray(boxW * boxH)
        val imgW = frame.width
        val imgH = frame.height

        val leftX = (boxX - 2).coerceIn(0, imgW - 1)
        val rightX = (boxX + boxW + 1).coerceIn(0, imgW - 1)
        val topY = (boxY - 2).coerceIn(0, imgH - 1)
        val bottomY = (boxY + boxH + 1).coerceIn(0, imgH - 1)

        val topRow = IntArray(boxW) { dx -> frame.getPixel((boxX + dx).coerceIn(0, imgW - 1), topY) }
        val bottomRow = IntArray(boxW) { dx -> frame.getPixel((boxX + dx).coerceIn(0, imgW - 1), bottomY) }
        val leftCol = IntArray(boxH) { dy -> frame.getPixel(leftX, (boxY + dy).coerceIn(0, imgH - 1)) }
        val rightCol = IntArray(boxH) { dy -> frame.getPixel(rightX, (boxY + dy).coerceIn(0, imgH - 1)) }

        for (dy in 0 until boxH) {
            val v = (dy + 1).toFloat() / (boxH + 1).toFloat()
            val distV = min(v, 1f - v)
            val leftPx = leftCol[dy]
            val rightPx = rightCol[dy]

            for (dx in 0 until boxW) {
                val u = (dx + 1).toFloat() / (boxW + 1).toFloat()
                val distU = min(u, 1f - u)

                val topPx = topRow[dx]
                val bottomPx = bottomRow[dx]

                // Inverse-distance weights to the 4 clean edges
                val wLeft = 1f / max(0.02f, u)
                val wRight = 1f / max(0.02f, 1f - u)
                val wTop = 1f / max(0.02f, v)
                val wBottom = 1f / max(0.02f, 1f - v)
                val totalW = wLeft + wRight + wTop + wBottom

                val r = ((AndroidColor.red(leftPx) * wLeft +
                        AndroidColor.red(rightPx) * wRight +
                        AndroidColor.red(topPx) * wTop +
                        AndroidColor.red(bottomPx) * wBottom) / totalW).toInt().coerceIn(0, 255)

                val g = ((AndroidColor.green(leftPx) * wLeft +
                        AndroidColor.green(rightPx) * wRight +
                        AndroidColor.green(topPx) * wTop +
                        AndroidColor.green(bottomPx) * wBottom) / totalW).toInt().coerceIn(0, 255)

                val b = ((AndroidColor.blue(leftPx) * wLeft +
                        AndroidColor.blue(rightPx) * wRight +
                        AndroidColor.blue(topPx) * wTop +
                        AndroidColor.blue(bottomPx) * wBottom) / totalW).toInt().coerceIn(0, 255)

                // Smoothly feather the outer 6% edge so there is no hard rectangle line
                val edgeFactor = (min(distU, distV) / 0.06f).coerceIn(0.25f, 1f)
                val alpha = (edgeFactor * 255f).toInt().coerceIn(0, 255)

                patch[dy * boxW + dx] = AndroidColor.argb(alpha, r, g, b)
            }
        }
        return patch
    }

    private fun saveToPhoneGallery(context: Context, videoFile: File): String {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, videoFile.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/PixelFixer")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val itemUri = resolver.insert(collection, values)
            if (itemUri != null) {
                resolver.openOutputStream(itemUri)?.use { outStream ->
                    FileInputStream(videoFile).use { inStream ->
                        inStream.copyTo(outStream)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(itemUri, values, null, null)
                }
                "Saved to Gallery (Movies/PixelFixer)!"
            } else {
                "Saved in App Studio!"
            }
        } catch (e: Exception) {
            "Saved in App Studio: ${videoFile.name}"
        }
    }
}

// 3. Main Activity
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                WatermarkRemoverApp()
            }
        }
    }
}

// 4. App Navigation
@Composable
fun WatermarkRemoverApp() {
    var currentTab by remember { mutableIntStateOf(0) }
    var selectedVideoUri by remember { mutableStateOf<Uri?>(null) }
    val processedVideos = remember { mutableStateListOf<File>() }
    var showPaywall by remember { mutableStateOf(false) }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedVideoUri = uri
            currentTab = 0
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PixelFixer", fontWeight = FontWeight.Bold) },
                actions = {
                    Button(
                        onClick = { showPaywall = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300))
                    ) {
                        Icon(Icons.Default.Star, contentDescription = null, tint = Color.Black)
                        Spacer(Modifier.width(4.dp))
                        Text("PRO", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    icon = { Icon(Icons.Default.AutoFixHigh, null) },
                    label = { Text("Editor") }
                )
                NavigationBarItem(
                    selected = currentTab == 1,
                    onClick = { currentTab = 1 },
                    icon = { Icon(Icons.Default.VideoLibrary, null) },
                    label = { Text("My Studio (${processedVideos.size})") }
                )
                NavigationBarItem(
                    selected = currentTab == 2,
                    onClick = { currentTab = 2 },
                    icon = { Icon(Icons.Default.WorkspacePremium, null) },
                    label = { Text("Pro") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (currentTab) {
                0 -> {
                    if (selectedVideoUri == null) {
                        HomeDashboard(onPickVideo = { videoPicker.launch("video/*") })
                    } else {
                        VideoEditorScreen(
                            videoUri = selectedVideoUri!!,
                            onChangeVideo = { videoPicker.launch("video/*") },
                            onSuccess = { file ->
                                processedVideos.add(0, file)
                                currentTab = 1
                            }
                        )
                    }
                }
                1 -> StudioGalleryScreen(processedVideos)
                2 -> ProPlanScreen()
            }
        }

        if (showPaywall) {
            ModalBottomSheet(onDismissRequest = { showPaywall = false }) {
                ProPlanScreen()
            }
        }
    }
}

@Composable
fun HomeDashboard(onPickVideo: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.MovieFilter,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "PixelFixer: Gemini & Video Eraser",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Powered by Google Media3 Hardware Engine. Select any video to erase its corner watermark.",
            color = Color.Gray,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onPickVideo,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Select Video from Gallery", fontSize = 16.sp)
        }
    }
}

@Composable
fun VideoEditorScreen(
    videoUri: Uri,
    onChangeVideo: () -> Unit,
    onSuccess: (File) -> Unit
) {
    val context = LocalContext.current
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val videoAspectRatio = remember(videoUri) { WatermarkProcessor.getVideoAspectRatio(context, videoUri) }

    // Default to Bottom-Right Corner (Standard Google Gemini Watermark Position)
    var boxLeft by remember { mutableFloatStateOf(0.72f) }
    var boxTop by remember { mutableFloatStateOf(0.86f) }
    var boxWidth by remember { mutableFloatStateOf(0.26f) }
    var boxHeight by remember { mutableFloatStateOf(0.12f) }

    var isProcessing by remember { mutableStateOf(false) }
    var progressPercent by remember { mutableIntStateOf(0) }
    var statusMessage by remember { mutableStateOf("Red box is set to Gemini Corner. Drag to adjust!") }
    var errorDialogMessage by remember { mutableStateOf<String?>(null) }

    val exoPlayer = remember(videoUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            repeatMode = ExoPlayer.REPEAT_MODE_ALL
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    if (isProcessing) {
        AlertDialog(
            onDismissRequest = { },
            confirmButton = {},
            title = { Text("Removing Watermark...", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "$progressPercent%",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = (progressPercent / 100f).coerceIn(0f, 1f),
                        modifier = Modifier.fillMaxWidth().height(10.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(statusMessage, textAlign = TextAlign.Center, fontSize = 13.sp)
                }
            }
        )
    }

    if (errorDialogMessage != null) {
        AlertDialog(
            onDismissRequest = { errorDialogMessage = null },
            confirmButton = {
                Button(onClick = { errorDialogMessage = null }) { Text("OK") }
            },
            title = { Text("Processing Info") },
            text = { Text(errorDialogMessage ?: "") }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF121212), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .aspectRatio(videoAspectRatio)
                    .fillMaxSize()
                    .onSizeChanged { containerSize = it }
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                if (containerSize.width > 0 && containerSize.height > 0) {
                                    boxLeft = (boxLeft + dragAmount.x / containerSize.width)
                                        .coerceIn(0.01f, max(0.01f, 0.99f - boxWidth))
                                    boxTop = (boxTop + dragAmount.y / containerSize.height)
                                        .coerceIn(0.01f, max(0.01f, 0.99f - boxHeight))
                                }
                            }
                        }
                ) {
                    val rectLeft = boxLeft * size.width
                    val rectTop = boxTop * size.height
                    val rectW = boxWidth * size.width
                    val rectH = boxHeight * size.height

                    drawRect(
                        color = Color.Red.copy(alpha = 0.30f),
                        topLeft = Offset(rectLeft, rectTop),
                        size = Size(rectW, rectH)
                    )
                    drawRect(
                        color = Color.Red,
                        topLeft = Offset(rectLeft, rectTop),
                        size = Size(rectW, rectH),
                        style = Stroke(width = 6f)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = {
                    boxWidth = 0.26f
                    boxHeight = 0.12f
                    boxLeft = 0.72f
                    boxTop = 0.86f
                    statusMessage = "Snapped to Bottom-Right Gemini Watermark!"
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("🎯 Gemini (Bottom-Right)", fontSize = 12.sp)
            }
            FilledTonalButton(
                onClick = {
                    boxWidth = 0.26f
                    boxHeight = 0.12f
                    boxLeft = 0.72f
                    boxTop = 0.03f
                    statusMessage = "Snapped to Top-Right Corner!"
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Top-Right Corner", fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(6.dp))
        Text("Box Width & Height:", fontSize = 12.sp, color = Color.Gray)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Slider(
                value = boxWidth,
                onValueChange = {
                    boxWidth = it.coerceIn(0.05f, 0.95f)
                    if (boxLeft + boxWidth > 0.99f) boxLeft = (0.99f - boxWidth).coerceAtLeast(0.01f)
                },
                modifier = Modifier.weight(1f)
            )
            Slider(
                value = boxHeight,
                onValueChange = {
                    boxHeight = it.coerceIn(0.05f, 0.95f)
                    if (boxTop + boxHeight > 0.99f) boxTop = (0.99f - boxHeight).coerceAtLeast(0.01f)
                },
                modifier = Modifier.weight(1f)
            )
        }

        Text(statusMessage, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onChangeVideo, modifier = Modifier.weight(1f)) {
                Text("Change Video")
            }
            Button(
                enabled = !isProcessing,
                onClick = {
                    exoPlayer.pause()
                    isProcessing = true
                    progressPercent = 1
                    statusMessage = "Starting Media3 Engine..."

                    WatermarkProcessor.removeWatermark(
                        context = context,
                        inputUri = videoUri,
                        normRect = NormalizedRect(boxLeft, boxTop, boxWidth, boxHeight),
                        onProgress = { pct, msg ->
                            progressPercent = pct
                            statusMessage = msg
                        },
                        onResult = { success, file, msg ->
                            isProcessing = false
                            statusMessage = msg
                            if (success && file != null) {
                                onSuccess(file)
                            } else {
                                errorDialogMessage = msg
                            }
                        }
                    )
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Remove Watermark")
            }
        }
    }
}

// 5. My Studio Screen with Video Preview + "Save to Any Folder" Picker
@Composable
fun StudioGalleryScreen(files: List<File>) {
    val context = LocalContext.current
    var selectedFile by remember { mutableStateOf(files.firstOrNull()) }
    var saveStatusText by remember { mutableStateOf("✅ Auto-saved to Gallery (Movies/PixelFixer)") }

    val saveToCustomFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("video/mp4")
    ) { destinationUri: Uri? ->
        if (destinationUri != null && selectedFile != null) {
            try {
                context.contentResolver.openOutputStream(destinationUri)?.use { outStream ->
                    FileInputStream(selectedFile!!).use { inStream ->
                        inStream.copyTo(outStream)
                    }
                }
                saveStatusText = "✅ Successfully saved to your chosen folder!"
                Toast.makeText(context, "Saved to selected folder!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                saveStatusText = "❌ Failed to save: ${e.localizedMessage}"
            }
        }
    }

    LaunchedEffect(files.size) {
        if (files.isNotEmpty()) {
            selectedFile = files.first()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("My Studio (Cleaned Videos)", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        if (files.isEmpty()) {
            Text(
                "No videos cleaned yet. Go to the Editor tab, select a Gemini video, and tap Remove Watermark!",
                color = Color.Gray
            )
        } else {
            Text(
                saveStatusText,
                color = Color(0xFF4CAF50),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))

            selectedFile?.let { file ->
                val previewPlayer = remember(file.absolutePath) {
                    ExoPlayer.Builder(context).build().apply {
                        setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                        repeatMode = ExoPlayer.REPEAT_MODE_ALL
                        prepare()
                        playWhenReady = true
                    }
                }
                DisposableEffect(previewPlayer) {
                    onDispose { previewPlayer.release() }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Now Playing Cleaned Video:", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp)
                                .background(Color.Black, RoundedCornerShape(8.dp))
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        player = previewPlayer
                                        useController = true
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        Button(
                            onClick = {
                                saveToCustomFolderLauncher.launch(file.name)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Save Video to Any Folder...")
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Processed History:", fontWeight = FontWeight.Bold)
            files.forEach { file ->
                Card(
                    onClick = { selectedFile = file },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(file.name, fontWeight = FontWeight.Bold)
                        Text("Tap to preview or save", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
fun ProPlanScreen() {
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        Text("Unlock PixelFixer PRO ✨", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("• Cloud AI Generative Inpainting")
        Text("• Batch Process up to 20 Reels/Shorts at once")
        Text("• 4K 60fps Original Quality Export")
        Text("• 100% Ad-Free Experience")
        Spacer(Modifier.height(20.dp))
        Button(onClick = { }, modifier = Modifier.fillMaxWidth()) {
            Text("Start 3-Day Free Trial (Then ₹299/month)")
        }
    }
}
