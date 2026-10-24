package com.pixelfixer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import java.io.FileOutputStream
import kotlin.math.max

// 1. Data Model
data class NormalizedRect(
    val leftPercent: Float,
    val topPercent: Float,
    val widthPercent: Float,
    val heightPercent: Float
)

// 2. Background Video Processor (Fixed Thread Crashing)
object WatermarkProcessor {
    fun removeWatermark(
        context: Context,
        inputUri: Uri,
        normRect: NormalizedRect,
        onProgress: (String) -> Unit,
        onResult: (Boolean, File?, String) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())

        // Run the heavy lifting in a background thread so the UI never freezes
        Thread {
            try {
                val inputFile = File(context.cacheDir, "input_video.mp4")
                context.contentResolver.openInputStream(inputUri)?.use { input ->
                    FileOutputStream(inputFile).use { output -> input.copyTo(output) }
                }

                val outputFile = File(context.getExternalFilesDir(null), "cleaned_${System.currentTimeMillis()}.mp4")

                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(inputFile.absolutePath)
                val videoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 1080
                val videoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 1920
                retriever.release()

                // Safe bounding box math (prevents crashes near screen edges)
                val x = (normRect.leftPercent * videoWidth).toInt().coerceIn(2, videoWidth - 20)
                val y = (normRect.topPercent * videoHeight).toInt().coerceIn(2, videoHeight - 20)
                
                val maxW = max(10, videoWidth - x - 2)
                val maxH = max(10, videoHeight - y - 2)
                
                val w = (normRect.widthPercent * videoWidth).toInt().coerceIn(10, maxW)
                val h = (normRect.heightPercent * videoHeight).toInt().coerceIn(10, maxH)

                val cmd = "-y -i \"${inputFile.absolutePath}\" -vf \"delogo=x=$x:y=$y:w=$w:h=$h:show=0\" -c:a copy \"${outputFile.absolutePath}\""

                FFmpegKit.executeAsync(cmd, { session ->
                    // Safely jump back to the Main UI Thread before updating the screen
                    mainHandler.post {
                        if (ReturnCode.isSuccess(session.returnCode)) {
                            onResult(true, outputFile, "Saved to: My Studio tab")
                        } else {
                            onResult(false, null, "Error: ${session.failStackTrace ?: "FFmpeg failed"}")
                        }
                    }
                }, { log ->
                    mainHandler.post { onProgress(log.message) }
                }, { _ -> })

            } catch (e: Exception) {
                mainHandler.post {
                    onResult(false, null, e.localizedMessage ?: "Unknown Error")
                }
            }
        }.start()
    }
}

// 3. Main Application Class
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

// 4. UI Navigation & Screens
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
                            onOpenPaywall = { showPaywall = true },
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
        Icon(Icons.Default.MovieFilter, contentDescription = null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("PixelFixer Studio", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Erase watermarks & logos instantly.", color = Color.Gray)
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
    onOpenPaywall: () -> Unit,
    onSuccess: (File) -> Unit
) {
    val context = LocalContext.current
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    var boxLeft by remember { mutableFloatStateOf(0.15f) }
    var boxTop by remember { mutableFloatStateOf(0.15f) }
    var boxWidth by remember { mutableFloatStateOf(0.35f) }
    var boxHeight by remember { mutableFloatStateOf(0.12f) }

    var isProcessing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Drag the red box over the watermark") }
    var selectedMode by remember { mutableIntStateOf(0) }

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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black, RoundedCornerShape(12.dp))
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
                                boxLeft = (boxLeft + dragAmount.x / containerSize.width).coerceIn(0.02f, 1f - boxWidth - 0.02f)
                                boxTop = (boxTop + dragAmount.y / containerSize.height).coerceIn(0.02f, 1f - boxHeight - 0.02f)
                            }
                        }
                    }
            ) {
                val rectLeft = boxLeft * size.width
                val rectTop = boxTop * size.height
                val rectW = boxWidth * size.width
                val rectH = boxHeight * size.height

                drawRect(
                    color = Color.Red.copy(alpha = 0.25f),
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

        Spacer(Modifier.height(12.dp))
        Text("Adjust Selection Box Size", fontSize = 12.sp, color = Color.Gray)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Slider(
                value = boxWidth,
                onValueChange = { boxWidth = it.coerceIn(0.05f, 1f - boxLeft - 0.02f) },
                modifier = Modifier.weight(1f)
            )
            Slider(
                value = boxHeight,
                onValueChange = { boxHeight = it.coerceIn(0.05f, 1f - boxTop - 0.02f) },
                modifier = Modifier.weight(1f)
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selectedMode == 0,
                onClick = { selectedMode = 0 },
                label = { Text("Standard Clean (Free)") }
            )
            FilterChip(
                selected = selectedMode == 1,
                onClick = {
                    selectedMode = 1
                    onOpenPaywall()
                },
                label = { Text("✨ AI Inpaint (PRO)") }
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(statusMessage, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onChangeVideo, modifier = Modifier.weight(1f)) {
                Text("Change Video")
            }
            Button(
                enabled = !isProcessing,
                onClick = {
                    isProcessing = true
                    statusMessage = "Processing video in background..."
                    
                    WatermarkProcessor.removeWatermark(
                        context = context,
                        inputUri = videoUri,
                        normRect = NormalizedRect(boxLeft, boxTop, boxWidth, boxHeight),
                        onProgress = { logMsg -> },
                        onResult = { success, file, msg ->
                            isProcessing = false
                            statusMessage = msg
                            if (success && file != null) onSuccess(file)
                        }
                    )
                },
                modifier = Modifier.weight(1f)
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Remove Watermark")
                }
            }
        }
    }
}

@Composable
fun StudioGalleryScreen(files: List<File>) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Processed Videos", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        if (files.isEmpty()) {
            Text("No videos cleaned yet. Go to the Editor tab to process your first video!", color = Color.Gray)
        } else {
            files.forEach { file ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(file.name, fontWeight = FontWeight.Bold)
                        Text("Saved to: ${file.absolutePath}", fontSize = 12.sp, color = Color.Gray)
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
