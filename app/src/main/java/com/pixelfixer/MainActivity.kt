package com.pixelfixer

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
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
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

// 1. Data Model
data class NormalizedRect(
    val leftPercent: Float,
    val topPercent: Float,
    val widthPercent: Float,
    val heightPercent: Float
)

// 2. Video Processor with Live 0% - 100% Progress & Gallery Export
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
                mainHandler.post { onProgress(2, "Copying video file...") }

                val inputFile = File(context.cacheDir, "input_video.mp4")
                context.contentResolver.openInputStream(inputUri)?.use { input ->
                    FileOutputStream(inputFile).use { output -> input.copyTo(output) }
                }

                val outputFile = File(context.getExternalFilesDir(null), "PixelFixer_${System.currentTimeMillis()}.mp4")

                mainHandler.post { onProgress(5, "Reading video dimensions...") }

                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(inputFile.absolutePath)
                val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
                val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 5000L
                retriever.release()

                // Account for portrait rotation metadata so coordinates match the visible video 1:1
                val videoWidth = if (rotation == 90 || rotation == 270) rawHeight else rawWidth
                val videoHeight = if (rotation == 90 || rotation == 270) rawWidth else rawHeight

                // FFmpeg delogo requires at least a 2-pixel border inside the frame edges
                val x = (normRect.leftPercent * videoWidth).toInt().coerceIn(2, max(4, videoWidth - 16))
                val y = (normRect.topPercent * videoHeight).toInt().coerceIn(2, max(4, videoHeight - 16))
                val w = (normRect.widthPercent * videoWidth).toInt().coerceIn(8, max(8, videoWidth - x - 4))
                val h = (normRect.heightPercent * videoHeight).toInt().coerceIn(8, max(8, videoHeight - y - 4))

                mainHandler.post { onProgress(10, "Erasing watermark (${w}x${h}px)...") }

                // Use built-in mpeg4 encoder (-c:v mpeg4 -q:v 2) so it works on 100% of Android phones
                val cmd = "-y -i \"${inputFile.absolutePath}\" -vf \"delogo=x=$x:y=$y:w=$w:h=$h:show=0\" -c:v mpeg4 -q:v 2 -c:a copy \"${outputFile.absolutePath}\""

                FFmpegKit.executeAsync(
                    cmd,
                    { session ->
                        if (ReturnCode.isSuccess(session.returnCode) && outputFile.exists() && outputFile.length() > 0) {
                            val galleryMsg = saveToPhoneGallery(context, outputFile)
                            mainHandler.post {
                                onProgress(100, "100% Complete!")
                                onResult(true, outputFile, galleryMsg)
                            }
                        } else {
                            val logs = session.allLogsAsString ?: "Unknown FFmpeg error"
                            val shortErr = if (logs.length > 250) logs.takeLast(250) else logs
                            mainHandler.post {
                                onResult(false, null, "FFmpeg Error:\n$shortErr")
                            }
                        }
                    },
                    { _ -> },
                    { stats ->
                        val processedMs = stats.time.toDouble()
                        if (durationMs > 0 && processedMs > 0) {
                            val rawPct = ((processedMs / durationMs.toDouble()) * 90.0).toInt()
                            val pct = (10 + rawPct).coerceIn(10, 99)
                            mainHandler.post {
                                onProgress(pct, "Removing watermark... $pct%")
                            }
                        }
                    }
                )
            } catch (e: Throwable) {
                mainHandler.post {
                    onResult(false, null, "Error: ${e.localizedMessage ?: "Failed to process video"}")
                }
            }
        }.start()
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
@OptIn(ExperimentalMaterial3Api::class)
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
        Text("PixelFixer: Gemini & Video Eraser", fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Select any Gemini or social video to remove its corner watermark.", color = Color.Gray, textAlign = TextAlign.Center)
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

    // Percentage Progress Dialog
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

    // Error Dialog (if anything goes wrong, you will see the exact reason)
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
        // Video + Locked Aspect-Ratio Overlay Box
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

        // Quick Preset Buttons for Gemini Watermark
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = {
                    // Bottom-Right Gemini Watermark Preset
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
                    // Top-Right Preset
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
                    statusMessage = "Starting watermark removal..."

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

// 5. My Studio Screen with Built-in Video Player to Verify Cleaned Video Immediately
@Composable
fun StudioGalleryScreen(files: List<File>) {
    val context = LocalContext.current
    var selectedFile by remember { mutableStateOf(files.firstOrNull()) }

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
                "✅ Also saved to your phone's Gallery under Movies/PixelFixer!",
                color = Color(0xFF4CAF50),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))

            // Preview Player for the Cleaned Video
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
                        Text("Tap to preview above", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
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

// 5. My Studio Screen with Video Preview + "Save to Any Folder" Picker
@Composable
fun StudioGalleryScreen(files: List<File>) {
    val context = LocalContext.current
    var selectedFile by remember { mutableStateOf(files.firstOrNull()) }
    var saveStatusText by remember { mutableStateOf("✅ Auto-saved to Gallery (Movies/PixelFixer)") }

    // Android System Folder & File Picker ("Save As...")
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
                android.widget.Toast.makeText(context, "Saved to selected folder!", android.widget.Toast.LENGTH_LONG).show()
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

            // Preview Player for the Cleaned Video
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

                        // Button to pick ANY folder on the phone and save the video there
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
