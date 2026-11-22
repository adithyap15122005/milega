package com.milega.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.milega.app.data.OcrLineDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max

/**
 * Photo handling for low-bandwidth networks: decode at reduced size, fix rotation,
 * re-encode as ~200-400 KB JPEG. The original full-size photo is never uploaded.
 */
object Media {
    private const val MAX_EDGE = 1600

    suspend fun prepareUpload(context: Context, source: Uri): Pair<File, Bitmap> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(source)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = resolver.openInputStream(source)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalArgumentException("Unreadable image")
        val rotation = resolver.openInputStream(source)!!.use { stream ->
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }
        var bitmap = if (rotation != 0f) {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation) }, true)
        } else decoded
        val longest = max(bitmap.width, bitmap.height)
        if (longest > MAX_EDGE) {
            val scale = MAX_EDGE.toFloat() / longest
            bitmap = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        }
        val dir = File(context.cacheDir, "scans").apply { mkdirs() }
        val out = File(dir, "scan-${UUID.randomUUID()}.jpg")
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        out to bitmap
    }

    fun newCaptureFile(context: Context): File {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        return File(dir, "capture-${UUID.randomUUID()}.jpg")
    }

    /** Remove old temporary photos so the app never fills a low-storage phone. */
    fun cleanup(context: Context, keep: File? = null) {
        listOf("scans", "captures").forEach { name ->
            File(context.cacheDir, name).listFiles()?.forEach { f ->
                if (f != keep && System.currentTimeMillis() - f.lastModified() > 60 * 60 * 1000) f.delete()
            }
        }
    }
}

/**
 * On-device OCR with Google ML Kit (offline, free). Runs the Latin and Devanagari
 * recognisers and returns de-duplicated lines with bounding boxes so the server can
 * group text by package and rebuild invoice rows.
 */
object Ocr {
    private val latin by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val devanagari by lazy { TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()) }

    suspend fun read(bitmap: Bitmap): List<OcrLineDto> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val results = mutableListOf<OcrLineDto>()
        val seen = HashSet<String>()
        var blockOffset = 0
        for (recognizer in listOf(latin, devanagari)) {
            val text: Text = try {
                recognizer.process(image).await()
            } catch (e: Exception) {
                continue // OCR is best effort; the photo is still uploaded
            }
            text.textBlocks.forEachIndexed { blockIndex, block ->
                for (line in block.lines) {
                    val box = line.boundingBox ?: continue
                    val clean = line.text.trim()
                    if (clean.length < 2) continue
                    val key = "${clean.lowercase()}@${box.centerX() / 20},${box.centerY() / 20}"
                    if (!seen.add(key)) continue
                    results += OcrLineDto(
                        text = clean, block = blockOffset + blockIndex,
                        left = box.left.toFloat(), top = box.top.toFloat(),
                        right = box.right.toFloat(), bottom = box.bottom.toFloat(),
                    )
                }
            }
            blockOffset += text.textBlocks.size
        }
        return results.take(400)
    }
}
