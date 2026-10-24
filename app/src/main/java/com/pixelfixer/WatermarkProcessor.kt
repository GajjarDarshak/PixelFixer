package com.pixelfixer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileOutputStream

data class NormalizedRect(
    val leftPercent: Float,
    val topPercent: Float,
    val widthPercent: Float,
    val heightPercent: Float
)

object WatermarkProcessor {
    fun removeWatermark(
        context: Context,
        inputUri: Uri,
        normRect: NormalizedRect,
        onProgress: (String) -> Unit,
        onResult: (Boolean, File?, String) -> Unit
    ) {
        try {
            val inputFile = File(context.cacheDir, "input_video.mp4")
            context.contentResolver.openInputStream(inputUri)?.use { input ->
                FileOutputStream(inputFile).use { output -> input.copyTo(output) }
            }

            val outputFile = File(context.getExternalFilesDir(null), "cleaned_${System.currentTimeMillis()}.mp4")

            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, inputUri)
            val videoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 1080
            val videoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 1920
            retriever.release()

            val x = (normRect.leftPercent * videoWidth).toInt().coerceIn(2, videoWidth - 20)
            val y = (normRect.topPercent * videoHeight).toInt().coerceIn(2, videoHeight - 20)
            val w = (normRect.widthPercent * videoWidth).toInt().coerceIn(10, videoWidth - x - 2)
            val h = (normRect.heightPercent * videoHeight).toInt().coerceIn(10, videoHeight - y - 2)

            val cmd = "-y -i \"${inputFile.absolutePath}\" -vf \"delogo=x=$x:y=$y:w=$w:h=$h:show=0\" -c:a copy \"${outputFile.absolutePath}\""

            FFmpegKit.executeAsync(cmd, { session ->
                if (ReturnCode.isSuccess(session.returnCode)) {
                    onResult(true, outputFile, "Saved to: ${outputFile.absolutePath}")
                } else {
                    onResult(false, null, "Error: ${session.failStackTrace ?: "FFmpeg failed"}")
                }
            }, { log ->
                onProgress(log.message)
            }, { _ -> })

        } catch (e: Exception) {
            onResult(false, null, e.localizedMessage ?: "Unknown Error")
        }
    }
}
