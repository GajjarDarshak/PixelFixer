@file:OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.pixelfixer

import android.app.Activity
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
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

// 1. Watermark Selection Area (0.0 to 1.0)
data class NormalizedRect(
    val leftPercent: Float,
    val topPercent: Float,
    val widthPercent: Float,
    val heightPercent: Float
)

// 2. Persistent Processed Video History Item
data class ProcessedVideo(
    val file: File,
    val shareUri: Uri?,
    val timestamp: Long = file.lastModified(),
    val modeName: String = "Seamless Erase"
)

// 3. Local Phone Storage Manager (Saves History & Edit Count Permanently on Device)
object LocalHistoryManager {
    private const val PREFS_NAME = "pixelfixer_local_store"
    private const val KEY_EDIT_COUNT = "total_edits_count"
    private const val KEY_HISTORY_JSON = "saved_video_history"

    fun getEditCount(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_EDIT_COUNT, 0)
    }

    fun incrementEditCount(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val next = prefs.getInt(KEY_EDIT_COUNT, 0) + 1
        prefs.edit().putInt(KEY_EDIT_COUNT, next).apply()
        return next
    }

    fun saveVideoEntry(context: Context, video: ProcessedVideo) {
        val currentList = loadHistory(context).toMutableList()
        currentList.removeAll { it.file.absolutePath == video.file.absolutePath }
        currentList.add(0, video)
        persistList(context, currentList)
    }

    fun deleteVideoEntry(context: Context, video: ProcessedVideo): List<ProcessedVideo> {
        try {
            if (video.file.exists()) video.file.delete()
        } catch (_: Exception) {}
        val currentList = loadHistory(context).toMutableList()
        currentList.removeAll { it.file.absolutePath == video.file.absolutePath }
        persistList(context, currentList)
        return currentList
    }

    fun loadHistory(context: Context): List<ProcessedVideo> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawJson = prefs.getString(KEY_HISTORY_JSON, null)
        val loaded = mutableListOf<ProcessedVideo>()
        val seenPaths = mutableSetOf<String>()

        if (!rawJson.isNullOrEmpty()) {
            try {
                val arr = JSONArray(rawJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val path = obj.optString("path")
                    val uriStr = obj.optString("uri", "")
                    val time = obj.optLong("timestamp", System.currentTimeMillis())
                    val mode = obj.optString("mode", "Seamless Erase")
                    val f = File(path)
                    if (f.exists() && f.length() > 0 && seenPaths.add(f.absolutePath)) {
                        val parsedUri = if (uriStr.isNotEmpty()) Uri.parse(uriStr) else null
                        loaded.add(ProcessedVideo(f, parsedUri, time, mode))
                    }
                }
            } catch (_: Exception) {}
        }

        // Also scan the app's local storage folder so no previously cleaned video is ever lost
        val dir = context.getExternalFilesDir(null)
        val diskFiles = dir?.listFiles { file ->
            file.isFile && file.name.startsWith("PixelFixer_") && file.name.endsWith(".mp4") && file.length() > 0
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

        for (f in diskFiles) {
            if (seenPaths.add(f.absolutePath)) {
                loaded.add(ProcessedVideo(f, null, f.lastModified(), "Seamless Erase"))
            }
        }

        return loaded.sortedByDescending { it.timestamp }
    }

    private fun persistList(context: Context, list: List<ProcessedVideo>) {
        val arr = JSONArray()
        for (item in list) {
            if (item.file.exists()) {
                val obj = JSONObject().apply {
                    put("path", item.file.absolutePath)
                    put("uri", item.shareUri?.toString() ?: "")
                    put("timestamp", item.timestamp)
                    put("mode", item.modeName)
                }
                arr.put(obj)
            }
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HISTORY_JSON, arr.toString())
            .apply()
    }
}

// 4. Google AdMob Manager (Preloads & Shows Interstitial Ad on 2nd+ Video Edit)
object AdMobManager {
    // Official Google AdMob Test Interstitial ID (Shows real test ads safely during validation)
    private const val INTERSTITIAL_AD_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"
    private var interstitialAd: InterstitialAd? = null
    private var isLoadingAd = false

    fun initializeAndPreload(context: Context) {
        try {
            MobileAds.initialize(context) {
                preloadAd(context)
            }
        } catch (_: Exception) {}
    }

    fun preloadAd(context: Context) {
        if (interstitialAd != null || isLoadingAd) return
        isLoadingAd = true
        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(
            context.applicationContext,
            INTERSTITIAL_AD_UNIT_ID,
            adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    isLoadingAd = false
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialAd = null
                    isLoadingAd = false
                }
            }
        )
    }

    fun showAdThenRun(
        activity: Activity,
        onAdStatus: (String) -> Unit,
        onContinueToProcess: () -> Unit
    ) {
        val readyAd = interstitialAd
        if (readyAd != null) {
            readyAd.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    interstitialAd = null
                    preloadAd(activity)
                    onContinueToProcess()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    interstitialAd = null
                    preloadAd(activity)
                    onContinueToProcess()
                }
            }
            readyAd.show(activity)
        } else {
            // If the ad wasn't preloaded yet, load it on the spot (with a 4-second timeout so the user never gets stuck)
            onAdStatus("Loading Google Ad before 2nd+ video edit...")
            var finished = false
            val mainHandler = Handler(Looper.getMainLooper())

            val timeoutRunnable = Runnable {
                if (!finished) {
                    finished = true
                    onContinueToProcess()
                }
            }
            mainHandler.postDelayed(timeoutRunnable, 4000L)

            val adRequest = AdRequest.Builder().build()
            InterstitialAd.load(
                activity.applicationContext,
                INTERSTITIAL_AD_UNIT_ID,
                adRequest,
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        if (finished) return
                        finished = true
                        mainHandler.removeCallbacks(timeoutRunnable)
                        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                            override fun onAdDismissedFullScreenContent() {
                                preloadAd(activity)
                                onContinueToProcess()
                            }

                            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                                preloadAd(activity)
                                onContinueToProcess()
                            }
                        }
                        ad.show(activity)
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        if (finished) return
                        finished = true
                        mainHandler.removeCallbacks(timeoutRunnable)
                        onContinueToProcess()
                    }
                }
            )
        }
    }
}

// 5. Per-Frame OpenGL GPU Watermark Eraser (Zero Box, Feathered Live Inpainting)
class DelogoGlEffect(
    private val normRect: NormalizedRect,
    private val removalMode: Int
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

                vec4 origColor = texture2D(uTexSampler, uv);

                if (uv.x < uLeft || uv.x > uRight || uv.y < uBottom || uv.y > uTop) {
                    gl_FragColor = origColor;
                    return;
                }

                float w = max(0.005, uRight - uLeft);
                float h = max(0.005, uTop - uBottom);
                float u = clamp((uv.x - uLeft) / w, 0.0, 1.0);
                float v = clamp((uv.y - uBottom) / h, 0.0, 1.0);

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

                float wL = 1.0 / pow(max(0.03, u), 1.35);
                float wR = 1.0 / pow(max(0.03, 1.0 - u), 1.35);
                float wB = 1.0 / pow(max(0.03, v), 1.35);
                float wT = 1.0 / pow(max(0.03, 1.0 - v), 1.35);

                if (uLeft < 0.03)   wL *= 0.02;
                if (uRight > 0.97)  wR *= 0.02;
                if (uBottom < 0.03) wB *= 0.02;
                if (uTop > 0.97)    wT *= 0.02;

                float totalW = wL + wR + wB + wT + 0.35;
                vec4 inpainted = (colLeft * wL + colRight * wR + colBottom * wB + colTop * wT + colDiagTL * 0.35) / totalW;

                vec2 centered = vec2((u - 0.5) * 2.0, (v - 0.5) * 2.0);
                float roundedDist = length(centered * centered * centered);
                float featherMask = 1.0 - smoothstep(0.45, 1.0, roundedDist);

                gl_FragColor = mix(origColor, inpainted, featherMask);
            }
        """
    }
}

// 6. Video Processor Manager
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
        val now = System.currentTimeMillis()
        val outputFile = File(context.getExternalFilesDir(null), "PixelFixer_$now.mp4")
        if (outputFile.exists()) outputFile.delete()

        val modeTitle = if (removalMode == 1) "Smart Edge Zoom" else "Seamless Erase"

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
                                val processedItem = ProcessedVideo(
                                    file = outputFile,
                                    shareUri = galleryUri,
                                    timestamp = now,
                                    modeName = modeTitle
                                )
                                // Save permanently in phone local storage history
                                LocalHistoryManager.saveVideoEntry(context, processedItem)
                                LocalHistoryManager.incrementEditCount(context)

                                mainHandler.post {
                                    onProgress(100, "100% Complete!")
                                    onResult(
                                        true,
                                        processedItem,
                                        "Saved to Local History & Gallery (Movies/PixelFixer)!"
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

// 7. Main Activity
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialize Google AdMob & preload the ad in the background
        AdMobManager.initializeAndPreload(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                WatermarkRemoverApp(activity = this)
            }
        }
    }
}

// 8. Main App Navigation (Loads Persistent Local History on Startup)
@Composable
fun WatermarkRemoverApp(activity: ComponentActivity) {
    val context = LocalContext.current
    var currentTab by remember { mutableIntStateOf(0) }
    var selectedVideoUri by remember { mutableStateOf<Uri?>(null) }

    // Load saved edit history from the user's phone storage immediately on launch
    val processedVideos = remember {
        mutableStateListOf<ProcessedVideo>().apply {
            addAll(LocalHistoryManager.loadHistory(context))
        }
    }
    var totalEditsDone by remember { mutableIntStateOf(LocalHistoryManager.getEditCount(context)) }
    var showPaywall by remember { mutableStateOf(false) }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            // Reset and set the new video URI so the Editor refreshes immediately
            selectedVideoUri = uri
            currentTab = 0
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PixelFixer", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            if (totalEditsDone == 0) "1st Video Edit: 100% Ad-Free ✨"
                            else "Edits Completed: $totalEditsDone",
                            fontSize = 11.sp,
                            color = Color(0xFF80CBC4)
                        )
                    }
                },
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
                    icon = { Icon(Icons.Default.History, null) },
                    label = { Text("My History (${processedVideos.size})") }
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
                        HomeDashboard(
                            totalEditsDone = totalEditsDone,
                            historyCount = processedVideos.size,
                            onPickVideo = { videoPicker.launch("video/*") },
                            onOpenHistory = { currentTab = 1 }
                        )
                    } else {
                        VideoEditorScreen(
                            activity = activity,
                            videoUri = selectedVideoUri!!,
                            totalEditsDone = totalEditsDone,
                            onChangeVideo = { videoPicker.launch("video/*") },
                            onSuccess = { processedVideo ->
                                processedVideos.removeAll { it.file.absolutePath == processedVideo.file.absolutePath }
                                processedVideos.add(0, processedVideo)
                                totalEditsDone = LocalHistoryManager.getEditCount(context)
                                currentTab = 1
                            }
                        )
                    }
                }
                1 -> StudioGalleryScreen(
                    videos = processedVideos,
                    onDeleteVideo = { toDelete ->
                        val updated = LocalHistoryManager.deleteVideoEntry(context, toDelete)
                        processedVideos.clear()
                        processedVideos.addAll(updated)
                    }
                )
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
fun HomeDashboard(
    totalEditsDone: Int,
    historyCount: Int,
    onPickVideo: () -> Unit,
    onOpenHistory: () -> Unit
) {
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
            if (totalEditsDone == 0) "Your first video watermark removal is 100% Ad-Free!"
            else "Select any video to erase its watermark. All edits are saved in your phone's local history.",
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

        if (historyCount > 0) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onOpenHistory,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.History, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("View Saved Edit History ($historyCount)")
            }
        }
    }
}

@Composable
fun VideoEditorScreen(
    activity: ComponentActivity,
    videoUri: Uri,
    totalEditsDone: Int,
    onChangeVideo: () -> Unit,
    onSuccess: (ProcessedVideo) -> Unit
) {
    val context = LocalContext.current
    var containerSize by remember(videoUri) { mutableStateOf(IntSize.Zero) }
    val videoAspectRatio = remember(videoUri) { WatermarkProcessor.getVideoAspectRatio(context, videoUri) }

    var boxLeft by remember(videoUri) { mutableFloatStateOf(0.78f) }
    var boxTop by remember(videoUri) { mutableFloatStateOf(0.88f) }
    var boxWidth by remember(videoUri) { mutableFloatStateOf(0.20f) }
    var boxHeight by remember(videoUri) { mutableFloatStateOf(0.10f) }
    var removalMode by remember { mutableIntStateOf(0) }

    var isProcessing by remember { mutableStateOf(false) }
    var progressPercent by remember { mutableIntStateOf(0) }
    var statusMessage by remember(videoUri) {
        mutableStateOf(
            if (totalEditsDone == 0) "1st Edit (No Ads): Place target over the Gemini logo."
            else "2nd+ Edit: A short Google Ad will play before processing."
        )
    }
    var errorDialogMessage by remember { mutableStateOf<String?>(null) }

    // Recreate ExoPlayer whenever videoUri changes
    val exoPlayer = remember(videoUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            repeatMode = ExoPlayer.REPEAT_MODE_ALL
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(videoUri) {
        onDispose {
            exoPlayer.stop()
            exoPlayer.release()
        }
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

    // Helper that starts the actual GPU watermark removal
    val startVideoProcessing = {
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
                // FIXED: key(videoUri) + update block forces PlayerView to switch to the new video immediately!
                key(videoUri) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = exoPlayer
                                useController = false
                            }
                        },
                        update = { playerView ->
                            if (playerView.player !== exoPlayer) {
                                playerView.player = exoPlayer
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(videoUri) {
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
                Text("Select Another Video")
            }
            Button(
                enabled = !isProcessing,
                onClick = {
                    exoPlayer.pause()

                    // 1st Video Edit (totalEditsDone == 0): NO AD! Process immediately.
                    // 2nd+ Video Edit (totalEditsDone >= 1): Show Google Ad first, then process!
                    val currentCount = LocalHistoryManager.getEditCount(context)
                    if (currentCount == 0) {
                        startVideoProcessing()
                    } else {
                        statusMessage = "Opening Google Ad for 2nd+ edit..."
                        AdMobManager.showAdThenRun(
                            activity = activity,
                            onAdStatus = { msg -> statusMessage = msg },
                            onContinueToProcess = { startVideoProcessing() }
                        )
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (totalEditsDone == 0) "Remove Watermark" else "Watch Ad & Remove")
            }
        }
    }
}

// 9. My History & Studio Screen (Persists Across App Restarts + Delete + Share)
@Composable
fun StudioGalleryScreen(
    videos: List<ProcessedVideo>,
    onDeleteVideo: (ProcessedVideo) -> Unit
) {
    val context = LocalContext.current
    var selectedVideo by remember(videos.size) { mutableStateOf(videos.firstOrNull()) }
    var saveStatusText by remember { mutableStateOf("✅ All edits are saved in your phone's Local History & Gallery") }
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()) }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("My Local Edit History (${videos.size})", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))

        if (videos.isEmpty()) {
            Text(
                "No edited videos in history yet. Go to the Editor tab to clean your first video (1st edit is 100% Ad-Free!).",
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
                DisposableEffect(video.file.absolutePath) {
                    onDispose {
                        previewPlayer.stop()
                        previewPlayer.release()
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Now Playing: ${video.file.name}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            "Edited: ${dateFormat.format(Date(video.timestamp))} • Mode: ${video.modeName}",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp)
                                .background(Color.Black, RoundedCornerShape(8.dp))
                        ) {
                            key(video.file.absolutePath) {
                                AndroidView(
                                    factory = { ctx ->
                                        PlayerView(ctx).apply {
                                            player = previewPlayer
                                            useController = true
                                        }
                                    },
                                    update = { playerView ->
                                        if (playerView.player !== previewPlayer) {
                                            playerView.player = previewPlayer
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

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

                        Spacer(Modifier.height(8.dp))

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
            Text("All Saved Edits on This Phone:", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))

            videos.forEach { video ->
                val isSelected = selectedVideo?.file?.absolutePath == video.file.absolutePath
                Card(
                    onClick = { selectedVideo = video },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(video.file.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(
                                "${dateFormat.format(Date(video.timestamp))} • ${video.modeName}",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                        IconButton(onClick = { onDeleteVideo(video) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF5350))
                        }
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
        Text("• 100% Ad-Free Unlimited Video Edits")
        Text("• Cloud AI Generative Inpainting")
        Text("• Batch Process up to 20 Reels/Shorts at once")
        Text("• 4K 60fps Original Quality Export")
        Spacer(Modifier.height(20.dp))
        Button(onClick = { }, modifier = Modifier.fillMaxWidth()) {
            Text("Start 3-Day Free Trial (Then ₹299/month)")
        }
    }
}
