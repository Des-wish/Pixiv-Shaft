package ceui.pixiv.ui.translate

import android.graphics.Bitmap
import ceui.pixiv.ui.upscale.OcrTextRegion
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bundled ML Kit engines supplement comic-text-detector and Japanese Manga-OCR on-device. */
object MangaMultilingualOcr {
    suspend fun recognize(bitmap: Bitmap, hint: MangaSourceLanguage): List<OcrTextRegion> {
        val languages = if (hint == MangaSourceLanguage.AUTO)
            listOf(MangaSourceLanguage.JAPANESE, MangaSourceLanguage.KOREAN, MangaSourceLanguage.ENGLISH)
        else listOf(hint)
        val found = mutableListOf<OcrTextRegion>()
        for (language in languages) {
            val recognizer = when (language) {
                MangaSourceLanguage.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                MangaSourceLanguage.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                MangaSourceLanguage.ENGLISH -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                MangaSourceLanguage.AUTO -> continue
            }
            try {
                for (y in tileStarts(bitmap.height)) for (x in tileStarts(bitmap.width)) {
                    coroutineContext.ensureActive()
                    val width = minOf(if (bitmap.width <= 2000) bitmap.width else 1600, bitmap.width - x)
                    val height = minOf(if (bitmap.height <= 2000) bitmap.height else 1600, bitmap.height - y)
                    val tile = Bitmap.createBitmap(bitmap, x, y, width, height)
                    try {
                        val result = recognizer.awaitResult(tile)
                        for (block in result.textBlocks) {
                            val box = block.boundingBox ?: continue
                            val content = block.text.trim()
                            if (!matchesLanguage(content, language)) continue
                            val left = x + box.left.toFloat()
                            val top = y + box.top.toFloat()
                            val right = x + box.right.toFloat()
                            val bottom = y + box.bottom.toFloat()
                            if (right <= left || bottom <= top) continue
                            val region = OcrTextRegion(
                                text = content, cx = (left + right) / 2f, cy = (top + bottom) / 2f,
                                width = right - left, height = bottom - top, angle = 0f,
                                orientation = 0, prob = 1f,
                                corners = listOf(left to top, right to top, right to bottom, left to bottom),
                                sourceLanguage = language,
                            )
                            if (found.none { it.sourceLanguage == language && overlap(it, region) > 0.7f }) found += region
                        }
                    } finally {
                        if (tile !== bitmap) tile.recycle()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "MangaMultilingualOcr: %s recognition failed", language.code)
            } finally {
                recognizer.close()
            }
        }
        // A Korean/English result carrying its actual script wins over a Japanese model hallucination.
        return found.filter { candidate ->
            candidate.sourceLanguage != MangaSourceLanguage.JAPANESE ||
                found.none { it.sourceLanguage != MangaSourceLanguage.JAPANESE && iou(it, candidate) > 0.65f }
        }
    }

    private fun tileStarts(length: Int): List<Int> = if (length <= 2000) listOf(0)
        else (0 until length step 1400).map { minOf(it, length - 1600) }.distinct()

    private fun matchesLanguage(text: String, language: MangaSourceLanguage): Boolean = when (language) {
        MangaSourceLanguage.JAPANESE -> text.count { it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9fff' } >= 1
        MangaSourceLanguage.KOREAN -> text.any { it in '\uac00'..'\ud7af' || it in '\u1100'..'\u11ff' }
        MangaSourceLanguage.ENGLISH -> text.count { it in 'A'..'Z' || it in 'a'..'z' } >= 2 &&
            text.none { it in '\u3040'..'\u30ff' || it in '\uac00'..'\ud7af' }
        MangaSourceLanguage.AUTO -> false
    }

    private fun overlap(a: OcrTextRegion, b: OcrTextRegion): Float {
        val l = maxOf(a.cx - a.width / 2, b.cx - b.width / 2)
        val t = maxOf(a.cy - a.height / 2, b.cy - b.height / 2)
        val r = minOf(a.cx + a.width / 2, b.cx + b.width / 2)
        val bot = minOf(a.cy + a.height / 2, b.cy + b.height / 2)
        return (r - l).coerceAtLeast(0f) * (bot - t).coerceAtLeast(0f) /
            minOf(a.width * a.height, b.width * b.height).coerceAtLeast(1f)
    }

    private fun iou(a: OcrTextRegion, b: OcrTextRegion): Float {
        val l = maxOf(a.cx - a.width / 2, b.cx - b.width / 2)
        val t = maxOf(a.cy - a.height / 2, b.cy - b.height / 2)
        val r = minOf(a.cx + a.width / 2, b.cx + b.width / 2)
        val bot = minOf(a.cy + a.height / 2, b.cy + b.height / 2)
        val intersection = (r - l).coerceAtLeast(0f) * (bot - t).coerceAtLeast(0f)
        return intersection / (a.width * a.height + b.width * b.height - intersection).coerceAtLeast(1f)
    }

    private suspend fun TextRecognizer.awaitResult(bitmap: Bitmap): Text = suspendCancellableCoroutine { cont ->
        process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
            .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    }
}
