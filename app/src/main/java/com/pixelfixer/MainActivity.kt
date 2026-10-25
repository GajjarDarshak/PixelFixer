@file:OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.pixelfixer

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.opengl.GLES20
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
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import java.io.File
import java.io.FileInputStream
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// 1. Watermark Selection Area (0.0 to 1.0)
data class NormalizedRect(
    val leftPercent: Float,
    val topPercent: Float,
    val widthPercent: Float,
    val heightPercent: Float
)

// 2. Processed Video Item (File + Shareable MediaStore URI for WhatsApp/Instagram)
data class ProcessedVideo(
    val file: File,
    val shareUri: Uri?
)

fun formatDurationMs(durationMs: Long): String {
    if (durationMs <= 0L) return "00:00.00"
    val totalHundredths = durationMs / 10L
    val minutes = totalHundredths / 6000L
    val seconds = (totalHundredths % 6000L) / 100L
    val hundredths = totalHundredths % 100L
    return "%02d:%02d.%02d".format(minutes, seconds, hundredths)
}

// 3. Per-Frame OpenGL GPU Watermark Eraser (Zero Box, Feathered Live Inpainting)
class DelogoGlEffect(
    private val normRect: NormalizedRect,
    private val removalMode: Int // 0 = Seamless Feather Erase, 1 = Smart Corner Zoom
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): BaseGlShaderProgram {
        return DelogoShaderProgram(useHdr, normRect, removalMode)
    }
}

class DelogoShaderProgram(
    useHdr: Boolean,
    normRect: NormalizedRect,
    removalMode: Int
) : BaseGlShaderProgram(useHdr, 1) {

    private val glProgram: GlProgram

    init {
        try {
            glProgram = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            glProgram.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
            )

            // Convert UI top-left coordinates (0..1) to OpenGL bottom-left UV coordinates (0..1)
            val glLeft = normRect.leftPercent.coerceIn(0.005f, 0.98f)
            val glRight = (normRect.leftPercent + normRect.widthPercent).coerceIn(glLeft + 0.02f, 0.995f)
            val glTop = (1f - normRect.topPercent).coerceIn(0.02f, 0.995f)
            val glBottom = (1f - (normRect.topPercent + normRect.heightPercent)).coerceIn(0.005f, glTop - 0.02f)

            glProgram.setFloatUniform("uLeft", glLeft)
            glProgram.setFloatUniform("uRight", glRight)
            glProgram.setFloatUniform("uBottom", glBottom)
            glProgram.setFloatUniform("uTop", glTop)
            glProgram.setFloatUniform("uMode", if (removalMode == 1) 1.0f else 0.0f)
        } catch (e: Exception) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): androidx.media3.common.util.Size {
        return androidx.media3.common.util.Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            glProgram.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    companion object {
        private const val VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
            }
        """

        // Per-frame GPU Inpainting Shader:
        // - Samples clean pixels outside the watermark on EVERY frame
        // - Automatically ignores screen edges when erasing corner watermarks (like Gemini)
        // - Uses an elliptical smoothstep feather so there is NEVER a visible box outline
        private const val FRAGMENT_SHADER = """
            precision highp float;
            varying vec2 vTexSamplingCoord;
            uniform sampler2D uTexSampler;
            uniform float uLeft;
            uniform float uRight;
            uniform float uBottom;
            uniform float uTop;
            uniform float uMode;

            vec4 sampleSmoothBorder(vec2 uv, vec2 dir, float span) {
                vec4 c1 = texture2D(uTexSampler, clamp(uv - dir * span, 0.002, 0.998));
                vec4 c2 = texture2D(uTexSampler, clamp(uv, 0.002, 0.998));
                vec4 c3 = texture2D(uTexSampler, clamp(uv + dir * span, 0.002, 0.998));
                return (c1 + c2 * 2.0 + c3) * 0.25;
            }

            void main() {
                vec2 uv = vTexSamplingCoord;

                // Mode 1: Smart Corner Zoom (pushes corner watermark outside frame with 0% blur)
                if (uMode > 0.5) {
                    float boxW = uRight - uLeft;
                    float boxH = uTop - uBottom;
                    float zoom = max(boxW, boxH) * 1.05;
                    vec2 anchor = vec2(
                        (uLeft + uRight) > 1.0 ? 0.0 : 1.0,
                        (uBottom + uTop) < 1.0 ? 1.0 : 0.0
                    );
                    vec2 zoomedUv = anchor + (uv - anchor) * (1.0 - zoom);
                    gl_FragColor = texture2D(uTexSampler, clamp(zoomedUv, 0.001, 0.999));
                    return;
                }

                // Mode 0: Seamless Per-Frame Feathered Inpaint
                vec4 origColor = texture2D(uTexSampler, uv);

                if (uv.x < uLeft || uv.x > uRight || uv.y < uBottom || uv.y > uTop) {
                    gl_FragColor = origColor;
                    return;
                }

                float w = max(0.005, uRight - uLeft);
                float h = max(0.005, uTop - uBottom);
                float u = clamp((uv.x - uLeft) / w, 0.0, 1.0);
                float v = clamp((uv.y - uBottom) / h, 0.0, 1.0);

                // Sample clean background just outside the box
                float pad = 0.008;
                float lX = max(0.003, uLeft - pad);
                float rX = min(0.997, uRight + pad);
                float bY = max(0.003, uBottom - pad);
                float tY = min(0.997, uTop + pad);

                vec4 colLeft   = sampleSmoothBorder(vec2(lX, uv.y), vec2(0.0, 1.0), h * 0.18);
                vec4 colRight  = sampleSmoothBorder(vec2(rX, uv.y), vec2(0.0, 1.0), h * 0.18);
                vec4 colBottom = sampleSmoothBorder(vec2(uv.x, bY), vec2(1.0, 0.0), w * 0.18);
                vec4 colTop    = sampleSmoothBorder(vec2(uv.x, tY), vec2(1.0, 0.0), w * 0.18);
                vec4 colDiagTL = texture2D(uTexSampler, vec2(lX, tY));

                // Inverse distance weights
                float wL = 1.0 / pow(max(0.03, u), 1.35);
                float wR = 1.0 / pow(max(0.03, 1.0 - u), 1.35);
                float wB = 1.0 / pow(max(0.03, v), 1.35);
                float wT = 1.0 / pow(max(0.03, 1.0 - v), 1.35);

                // If the box is at the edge/corner of the screen (e.g. Gemini bottom-right),
                // disable sampling from the screen edge so only clean interior video is used!
                if (uLeft < 0.03)   wL *= 0.02;
                if (uRight > 0.97)  wR *= 0.02;
                if (uBottom < 0.03) wB *= 0.02;
                if (uTop > 0.97)    wT *= 0.02;

                float totalW = wL + wR + wB + wT + 0.35;
                vec4 inpainted = (colLeft * wL + colRight * wR + colBottom * wB + colTop * wT + colDiagTL * 0.35) / totalW;

                // Soft elliptical feather mask: fades smoothly at the edges so no box outline is visible
                vec2 centered = vec2((u - 0.5) * 2.0, (v - 0.5) * 2.0);
                float roundedDist = length(centered * centered * centered); // Super-ellipse shape
                float featherMask = 1.0 - smoothstep(0.45, 1.0, roundedDist);

                gl_FragColor = mix(origColor, inpainted, featherMask);
            }
        """
    }
}

// 4. Video Processor Manager
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
        removalMode: Int,
        onProgress: (Int, String) -> Unit,
        onResult: (Boolean, ProcessedVideo?, String) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())

        val outputFile = File(
            context.getExternalFilesDir(null),
            "PixelFixer_${System.currentTimeMillis()}.mp4"
        )
        if (outputFile.exists()) outputFile.delete()

        mainHandler.post {
            try {
                onProgress(5, "Initializing GPU Shader Engine...")

                val delogoEffect = DelogoGlEffect(normRect, removalMode)
                val effects = Effects(emptyList(), listOf<Effect>(delogoEffect))
                val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(inputUri))
                    .setEffects(effects)
                    .build()

                var isFinished = false

                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            isFinished = true
                            Thread {
                                val galleryUri = saveToPhoneGallery(context, outputFile)
                                mainHandler.post {
                                    onProgress(100, "100% Complete!")
                                    onResult(
                                        true,
                                        ProcessedVideo(outputFile, galleryUri),
                                        "Saved to Gallery (Movies/PixelFixer)!"
                                    )
                                }
                            }.start()
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            isFinished = true
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

                val progressHolder = ProgressHolder()
                val progressRunnable = object : Runnable {
                    override fun run() {
                        if (isFinished) return
                        val state = transformer.getProgress(progressHolder)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            val pct = (5 + (progressHolder.progress * 94 / 100)).coerceIn(5, 99)
                            onProgress(pct, "Removing watermark... $pct%")
                        }
                        if (state != Transformer.PROGRESS_STATE_NOT_STARTED && !isFinished) {
                            mainHandler.postDelayed(this, 150L)
                        }
                    }
                }
                mainHandler.postDelayed(progressRunnable, 150L)

            } catch (e: Throwable) {
                onResult(false, null, "Error: ${e.localizedMessage ?: "Failed to process"}")
            }
        }
    }

    fun saveToPhoneGallery(context: Context, videoFile: File): Uri? {
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
            }
            itemUri
        } catch (e: Exception) {
            null
        }
    }

    fun shareVideo(context: Context, video: ProcessedVideo, targetPackage: String?) {
        try {
            val contentUri = video.shareUri ?: saveToPhoneGallery(context, video.file)
            if (contentUri == null) {
                Toast.makeText(context, "Could not prepare video for sharing", Toast.LENGTH_SHORT).show()
                return
            }

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (targetPackage != null) {
                    setPackage(targetPackage)
                }
            }

            try {
                context.startActivity(
                    if (targetPackage != null) intent else Intent.createChooser(intent, "Share Cleaned Video")
                )
            } catch (e: Exception) {
                // If WhatsApp or Instagram isn't installed, fall back to the universal system share sheet
                val fallbackIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(fallbackIntent, "Share Cleaned Video"))
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Share error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }
}

// 5. Main Activity
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

// 6. Main App Navigation
@Composable
fun WatermarkRemoverApp() {
    var currentTab by remember { mutableIntStateOf(0) }
    var selectedVideoUri by remember { mutableStateOf<Uri?>(null) }
    val processedVideos = remember { mutableStateListOf<ProcessedVideo>() }
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
                            onSuccess = { processedVideo ->
                                processedVideos.add(0, processedVideo)
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
            "Real-Time GPU Watermark Eraser. Removes corner watermarks smoothly without adding boxes.",
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
    onSuccess: (ProcessedVideo) -> Unit
) {
    val context = LocalContext.current
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val videoAspectRatio = remember(videoUri) { WatermarkProcessor.getVideoAspectRatio(context, videoUri) }

    // Tighter default fit for the Google Gemini bottom-right sparkle/logo
    var boxLeft by remember { mutableFloatStateOf(0.78f) }
    var boxTop by remember { mutableFloatStateOf(0.88f) }
    var boxWidth by remember { mutableFloatStateOf(0.20f) }
    var boxHeight by remember { mutableFloatStateOf(0.10f) }

    // 0 = Seamless GPU Erase (Feathered), 1 = Smart Corner Zoom (100% Zero Blur)
    var removalMode by remember { mutableIntStateOf(0) }

    var isProcessing by remember { mutableStateOf(false) }
    var progressPercent by remember { mutableIntStateOf(0) }
    var statusMessage by remember { mutableStateOf("Place the rounded target tightly over the Gemini logo.") }
    var errorDialogMessage by remember { mutableStateOf<String?>(null) }

    val exoPlayer = remember(videoUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            repeatMode = ExoPlayer.REPEAT_MODE_ALL
            prepare()
            playWhenReady = true
        }
    }

    val videoDurationMs = remember(videoUri) { exoPlayer.duration.takeIf { it > 0L } ?: 0L }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var selectionStartMs by remember(videoUri) { mutableLongStateOf(0L) }
    var selectionEndMs by remember(videoUri) { mutableLongStateOf(0L) }

    LaunchedEffect(videoUri, videoDurationMs) {
        if (videoDurationMs > 0L) {
            if (selectionStartMs <= 0L && selectionEndMs <= 0L) {
                selectionStartMs = 0L
                selectionEndMs = videoDurationMs
            }
            if (selectionEndMs <= selectionStartMs || selectionEndMs > videoDurationMs) {
                selectionEndMs = videoDurationMs
            }
        }
    }

    LaunchedEffect(exoPlayer) {
        while (isActive) {
            currentPositionMs = exoPlayer.currentPosition
            delay(120L)
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

                // Rounded Target Overlay (shows exact feathered erase zone)
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                if (containerSize.width > 0 && containerSize.height > 0) {
                                    boxLeft = (boxLeft + dragAmount.x / containerSize.width)
                                        .coerceIn(0.005f, max(0.005f, 0.995f - boxWidth))
                                    boxTop = (boxTop + dragAmount.y / containerSize.height)
                                        .coerceIn(0.005f, max(0.005f, 0.995f - boxHeight))
                                }
                            }
                        }
                ) {
                    val rectLeft = boxLeft * size.width
                    val rectTop = boxTop * size.height
                    val rectW = boxWidth * size.width
                    val rectH = boxHeight * size.height

                    drawRoundRect(
                        color = Color(0xFF00E5FF).copy(alpha = 0.22f),
                        topLeft = Offset(rectLeft, rectTop),
                        size = Size(rectW, rectH),
                        cornerRadius = CornerRadius(rectW * 0.35f, rectH * 0.35f)
                    )
                    drawRoundRect(
                        color = Color(0xFF00E5FF),
                        topLeft = Offset(rectLeft, rectTop),
                        size = Size(rectW, rectH),
                        cornerRadius = CornerRadius(rectW * 0.35f, rectH * 0.35f),
                        style = Stroke(width = 5f)
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Video Editor Bar", fontWeight = FontWeight.Bold)
                    Text(
                        text = "${formatDurationMs(currentPositionMs)} / ${formatDurationMs(videoDurationMs)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (videoDurationMs > 0L) {
                    Slider(
                        value = selectionStartMs.toFloat(),
                        onValueChange = {
                            val value = it.toLong().coerceIn(0L, videoDurationMs)
                            selectionStartMs = value
                            if (selectionEndMs <= selectionStartMs) {
                                selectionEndMs = (selectionStartMs + 1000L).coerceAtMost(videoDurationMs)
                            }
                            exoPlayer.seekTo(selectionStartMs)
                        },
                        valueRange = 0f..videoDurationMs.toFloat(),
                    )

                    Slider(
                        value = selectionEndMs.toFloat(),
                        onValueChange = {
                            val value = it.toLong().coerceIn(0L, videoDurationMs)
                            selectionEndMs = value
                            if (selectionEndMs <= selectionStartMs) {
                                selectionStartMs = (selectionEndMs - 1000L).coerceAtLeast(0L)
                            }
                        },
                        valueRange = selectionStartMs.toFloat()..videoDurationMs.toFloat(),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Start: ${formatDurationMs(selectionStartMs)}", fontSize = 12.sp)
                    Text("Stop: ${formatDurationMs(selectionEndMs)}", fontSize = 12.sp)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            exoPlayer.seekTo(selectionStartMs.coerceAtMost(videoDurationMs))
                            exoPlayer.play()
                            statusMessage = "Playing from selected start time."
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Play")
                    }
                    Button(
                        onClick = {
                            exoPlayer.pause()
                            statusMessage = "Video paused."
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("Pause")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            selectionStartMs = currentPositionMs.coerceAtMost(videoDurationMs)
                            if (selectionEndMs <= selectionStartMs) {
                                selectionEndMs = (selectionStartMs + 1000L).coerceAtMost(videoDurationMs)
                            }
                            exoPlayer.seekTo(selectionStartMs)
                            statusMessage = "Start point set to ${formatDurationMs(selectionStartMs)}."
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Set Start")
                    }
                    OutlinedButton(
                        onClick = {
                            selectionEndMs = currentPositionMs.coerceAtLeast(selectionStartMs).coerceAtMost(videoDurationMs)
                            if (selectionEndMs <= selectionStartMs) {
                                selectionEndMs = (selectionStartMs + 1000L).coerceAtMost(videoDurationMs)
                            }
                            statusMessage = "Stop point set to ${formatDurationMs(selectionEndMs)}."
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Set Stop")
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Removal Mode Selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = removalMode == 0,
                onClick = {
                    removalMode = 0
                    statusMessage = "Seamless Erase: Smoothly blends live pixels over the watermark."
                },
                label = { Text("✨ Seamless Erase", fontSize = 12.sp) },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = removalMode == 1,
                onClick = {
                    removalMode = 1
                    statusMessage = "Smart Edge Zoom: Pushes corner watermark out with 100% sharp pixels."
                },
                label = { Text("🔍 Smart Edge Zoom", fontSize = 12.sp) },
                modifier = Modifier.weight(1f)
            )
        }

        // Quick Corner Presets
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = {
                    boxWidth = 0.20f
                    boxHeight = 0.10f
                    boxLeft = 0.78f
                    boxTop = 0.88f
                    statusMessage = "Snapped tightly to Bottom-Right Gemini Corner!"
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("🎯 Gemini (Bottom-Right)", fontSize = 12.sp)
            }
            FilledTonalButton(
                onClick = {
                    boxWidth = 0.20f
                    boxHeight = 0.10f
                    boxLeft = 0.78f
                    boxTop = 0.02f
                    statusMessage = "Snapped to Top-Right Corner!"
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Top-Right Corner", fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(4.dp))
        Text("Adjust Target Width & Height:", fontSize = 12.sp, color = Color.Gray)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Slider(
                value = boxWidth,
                onValueChange = {
                    boxWidth = it.coerceIn(0.04f, 0.60f)
                    if (boxLeft + boxWidth > 0.995f) boxLeft = (0.995f - boxWidth).coerceAtLeast(0.005f)
                },
                modifier = Modifier.weight(1f)
            )
            Slider(
                value = boxHeight,
                onValueChange = {
                    boxHeight = it.coerceIn(0.04f, 0.40f)
                    if (boxTop + boxHeight > 0.995f) boxTop = (0.995f - boxHeight).coerceAtLeast(0.005f)
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
                    statusMessage = "Starting GPU Watermark Eraser..."

                    WatermarkProcessor.removeWatermark(
                        context = context,
                        inputUri = videoUri,
                        normRect = NormalizedRect(boxLeft, boxTop, boxWidth, boxHeight),
                        removalMode = removalMode,
                        onProgress = { pct, msg ->
                            progressPercent = pct
                            statusMessage = msg
                        },
                        onResult = { success, processedVideo, msg ->
                            isProcessing = false
                            statusMessage = msg
                            if (success && processedVideo != null) {
                                onSuccess(processedVideo)
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

// 7. My Studio Screen with Video Player + WhatsApp, Instagram & Save Folder Buttons
@Composable
fun StudioGalleryScreen(videos: List<ProcessedVideo>) {
    val context = LocalContext.current
    var selectedVideo by remember { mutableStateOf(videos.firstOrNull()) }
    var saveStatusText by remember { mutableStateOf("✅ Auto-saved to Gallery (Movies/PixelFixer)") }

    val saveToCustomFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("video/mp4")
    ) { destinationUri: Uri? ->
        if (destinationUri != null && selectedVideo != null) {
            try {
                context.contentResolver.openOutputStream(destinationUri)?.use { outStream ->
                    FileInputStream(selectedVideo!!.file).use { inStream ->
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

    LaunchedEffect(videos.size) {
        if (videos.isNotEmpty()) {
            selectedVideo = videos.first()
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

        if (videos.isEmpty()) {
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

            selectedVideo?.let { video ->
                val previewPlayer = remember(video.file.absolutePath) {
                    ExoPlayer.Builder(context).build().apply {
                        setMediaItem(MediaItem.fromUri(Uri.fromFile(video.file)))
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

                        // 1-Tap WhatsApp & Instagram Share Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    WatermarkProcessor.shareVideo(context, video, "com.whatsapp")
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Send, contentDescription = null, tint = Color.White)
                                Spacer(Modifier.width(6.dp))
                                Text("WhatsApp", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }

                            Button(
                                onClick = {
                                    WatermarkProcessor.shareVideo(context, video, "com.instagram.android")
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE1306C)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, tint = Color.White)
                                Spacer(Modifier.width(6.dp))
                                Text("Instagram", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Save to Any Folder + General Share Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    saveToCustomFolderLauncher.launch(video.file.name)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Save Folder", fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = {
                                    WatermarkProcessor.shareVideo(context, video, null)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.MoreHoriz, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("More Apps", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Processed History:", fontWeight = FontWeight.Bold)
            videos.forEach { video ->
                Card(
                    onClick = { selectedVideo = video },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(video.file.name, fontWeight = FontWeight.Bold)
                        Text("Tap to preview or share", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
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
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300))
        ) {
            Text("Upgrade to PRO", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}
