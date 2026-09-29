package ceui.pixiv.ui.translate

import android.graphics.Bitmap
import android.graphics.Color
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import ceui.pixiv.ui.upscale.OcrTextRegion
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Erases text from manga pages.
 *
 * 两条路径,**首选 [textMask] 路径**(CTD 模型直接吐的 text segmentation mask):
 *  - mask 路径:对每个 region AABB,在 mask 上读「这个像素是不是文字」,膨胀 1 次 → 覆盖背景色。
 *    擦除形状跟模型的 glyph segmentation 一致,贴字符轮廓不出方块。
 *    mask 分辨率可以低于底图(OCR 在降采样图上跑,回填在原分辨率上做),按比例最近邻取样。
 *  - fallback 路径(mask 为 null):退回到颜色阈值,同 region 内
 *    与采样背景色单通道最大差 > [INK_THRESHOLD] 判为墨。粗糙但不至于崩。
 *
 * 两条路径都用同一个 [sampleBackgroundColor] 拿背景色作填色,差别只在「哪些像素被判为字」。
 *
 * 像素常量按「短边 ≤ [MangaPageTranslatePipeline.LAYOUT_REFERENCE_SHORT_SIDE]」的图调过;
 * 更高分辨率的底图由调用方传 `pxScale` 等比放大,保证擦除效果与分辨率无关。
 */
object TextEraser {

    /** AABB 略外扩,兜底 region 框比 glyph 略小的情况。气泡内部留白通常 > 这个值,不会碰到黑边。 */
    private const val AABB_PAD_PX = 2

    /** fallback 颜色阈值。manga 黑字白底差 ~255,留宽容防底色噪点误判。 */
    private const val INK_THRESHOLD = 64

    /** 墨 mask 8 邻域膨胀次数,吃 glyph 抗锯齿灰边;>=2 会开始把相邻笔画粘成块,1 比较稳。 */
    private const val DILATE_PX = 1

    /** 背景色采样:在 region 框外这么远的一圈上取样。 */
    private const val BG_SAMPLE_MARGIN_PX = 6

    /** 背景色采样步长。 */
    private const val BG_SAMPLE_STEP_PX = 2

    /**
     * 原地擦掉 [bitmap] 上 [regions] 里的原文。整页位图只留一份(原分辨率回填时一份就是上百 MB),
     * 所以不再 copy;背景色在动笔前一次采完,结果与「在副本上擦」一致。
     *
     * @param bitmap 必须 mutable
     * @param textMask CTD 给的像素级文本 mask,覆盖与 [bitmap] 相同的画面(分辨率可以更低);
     *  非 null 时走 mask 路径,null 时退回颜色阈值。
     * @param pxScale 底图相对参考分辨率的像素密度倍数,放大外扩 / 膨胀 / 采样边距
     */
    fun eraseText(
        bitmap: Bitmap,
        regions: List<OcrTextRegion>,
        textMask: TextMask? = null,
        pxScale: Float = 1f,
        backgroundOverride: Int? = null,
    ): Int {
        require(bitmap.isMutable) { "eraseText needs a mutable bitmap" }
        val mask = textMask?.takeIf { it.width > 0 && it.height > 0 }
        val pad = scaledPx(AABB_PAD_PX, pxScale)
        val dilate = scaledPx(DILATE_PX, pxScale)
        Timber.d(
            "TextEraser: bitmap %dx%d, %d regions, mode=%s, pxScale=%.2f",
            bitmap.width, bitmap.height, regions.size,
            if (mask != null) "mask ${mask.width}x${mask.height}" else "threshold-fallback", pxScale
        )
        // 先采完再擦:相邻 region 的采样圈可能压到前一个刚擦过的像素上
        val bgColors = regions.map { backgroundOverride ?: sampleBackgroundColor(bitmap, it, pxScale) }
        var totalErased = 0
        for ((i, region) in regions.withIndex()) {
            val bgColor = bgColors[i]
            val erased = if (mask != null) {
                eraseRegionByMask(bitmap, region, bgColor, mask, pad, dilate)
                    .takeIf { it > 0 } ?: eraseRegionByThreshold(bitmap, region, bgColor, pad, dilate)
            } else {
                eraseRegionByThreshold(bitmap, region, bgColor, pad, dilate)
            }
            totalErased += erased
            if (i < 2) {
                val xs = region.corners.map { it.first }
                val ys = region.corners.map { it.second }
                Timber.d(
                    "TextEraser: region[%d] AABB=[%.0f,%.0f,%.0f,%.0f] bg=#%06X ink=%d px",
                    i, xs.min(), ys.min(), xs.max(), ys.max(), bgColor and 0xFFFFFF, erased
                )
            }
        }
        return totalErased
    }

    /**
     * Mask 路径:模型 mask 已经是 glyph segmentation,这里只做 AABB 裁剪 + 1 次膨胀。
     * 返回擦除的像素数。
     */
    private fun eraseRegionByMask(
        bmp: Bitmap, region: OcrTextRegion, bgColor: Int, textMask: TextMask, pad: Int, dilate: Int,
    ): Int {
        val aabb = regionAabb(region, bmp.width, bmp.height, pad) ?: return 0
        val x0 = aabb[0]; val y0 = aabb[1]; val x1 = aabb[2]; val y1 = aabb[3]
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        if (w <= 1 || h <= 1) return 0

        // 底图像素 → mask 像素的最近邻映射;同分辨率时就是恒等
        val maskData = textMask.data
        val maskW = textMask.width
        val maskH = textMask.height
        val coverage = textMask.coverage
        val maskCols = IntArray(w) { dx ->
            if (coverage == null) ((x0 + dx).toLong() * maskW / bmp.width).toInt().coerceIn(0, maskW - 1)
            else ((x0 + dx - coverage.left).toLong() * maskW / (coverage.right - coverage.left).coerceAtLeast(1)).toInt()
        }
        var local = BooleanArray(w * h)
        for (dy in 0 until h) {
            val maskY = if (coverage == null) ((y0 + dy).toLong() * maskH / bmp.height).toInt().coerceIn(0, maskH - 1)
                else ((y0 + dy - coverage.top).toLong() * maskH / (coverage.bottom - coverage.top).coerceAtLeast(1)).toInt()
            if (maskY !in 0 until maskH) continue
            val maskRow = maskY * maskW
            val dstRow = dy * w
            for (dx in 0 until w) {
                val mx = maskCols[dx]
                if (mx in 0 until maskW && maskData[maskRow + mx].toInt() != 0) local[dstRow + dx] = true
            }
        }
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, x0, y0, w, h)
        // The segmentation head often misses antialiased glyph edges. Add nearby dark/light
        // pixels that differ from the sampled bubble background, but never scan the whole box.
        val nearby = dilate8(dilate8(local, w, h), w, h)
        val bgR = Color.red(bgColor); val bgG = Color.green(bgColor); val bgB = Color.blue(bgColor)
        for (i in local.indices) if (nearby[i]) {
            val p = pixels[i]
            if (maxOf(abs(Color.red(p) - bgR), abs(Color.green(p) - bgG),
                    abs(Color.blue(p) - bgB)) > INK_THRESHOLD / 2) local[i] = true
        }
        repeat(dilate) { local = dilate8(local, w, h) }
        return repairRegion(bmp, pixels, local, x0, y0, w, h, bgColor)
    }

    /**
     * Fallback 路径:在 AABB 内做 color threshold + dilate。质量比 mask 路径差,
     * 只在 CTD 没吐 mask / mask 尺寸对不上时启用。
     */
    private fun eraseRegionByThreshold(
        bmp: Bitmap, region: OcrTextRegion, bgColor: Int, pad: Int, dilate: Int,
    ): Int {
        val aabb = regionAabb(region, bmp.width, bmp.height, pad) ?: return 0
        val x0 = aabb[0]; val y0 = aabb[1]; val x1 = aabb[2]; val y1 = aabb[3]
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        if (w <= 1 || h <= 1) return 0

        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, x0, y0, w, h)

        val bgR = Color.red(bgColor)
        val bgG = Color.green(bgColor)
        val bgB = Color.blue(bgColor)

        var mask = BooleanArray(w * h)
        for (idx in pixels.indices) {
            val p = pixels[idx]
            val dr = abs(Color.red(p) - bgR)
            val dg = abs(Color.green(p) - bgG)
            val db = abs(Color.blue(p) - bgB)
            if (maxOf(dr, dg, db) > INK_THRESHOLD) mask[idx] = true
        }
        repeat(dilate) { mask = dilate8(mask, w, h) }

        // A color threshold cannot distinguish ink from artwork on a textured background.
        if (mask.count { it } > mask.size * 0.32f || backgroundSpread(pixels, mask) > 28) {
            Timber.d("TextEraser: unreliable threshold mask on textured region, preserving original")
            return 0
        }
        return repairRegion(bmp, pixels, mask, x0, y0, w, h, bgColor)
    }

    private fun backgroundSpread(pixels: IntArray, mask: BooleanArray): Int {
        var min = 255
        var max = 0
        var count = 0
        for (i in pixels.indices step maxOf(1, pixels.size / 128)) {
            if (mask[i]) continue
            val p = pixels[i]
            val luma = (Color.red(p) * 77 + Color.green(p) * 150 + Color.blue(p) * 29) shr 8
            min = minOf(min, luma)
            max = maxOf(max, luma)
            count++
        }
        return if (count == 0) 255 else max - min
    }

    /** Flat bubbles are filled exactly; textured bubbles use local Telea inpainting. */
    private fun repairRegion(bmp: Bitmap, pixels: IntArray, mask: BooleanArray,
        x0: Int, y0: Int, w: Int, h: Int, bgColor: Int): Int {
        val painted = mask.count { it }
        if (painted == 0) return 0
        if (backgroundSpread(pixels, mask) > 18 && runCatching {
                require(OpenCVLoader.initLocal())
                val patch = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val rgba = Mat()
                val rgb = Mat()
                val maskMat = Mat(h, w, CvType.CV_8UC1)
                val repaired = Mat()
                val result = Mat()
                try {
                    patch.setPixels(pixels, 0, w, 0, 0, w, h)
                    Utils.bitmapToMat(patch, rgba)
                    Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
                    maskMat.put(0, 0, ByteArray(mask.size) { if (mask[it]) 255.toByte() else 0 })
                    Photo.inpaint(rgb, maskMat, repaired, 3.0, Photo.INPAINT_TELEA)
                    Imgproc.cvtColor(repaired, result, Imgproc.COLOR_RGB2RGBA)
                    Utils.matToBitmap(result, patch)
                    patch.getPixels(pixels, 0, w, 0, 0, w, h)
                } finally {
                    patch.recycle(); rgba.release(); rgb.release(); maskMat.release()
                    repaired.release(); result.release()
                }
            }.onFailure { Timber.w(it, "TextEraser: inpaint failed, using local fill") }.isSuccess) {
            bmp.setPixels(pixels, 0, w, x0, y0, w, h)
            return painted
        }
        for (i in mask.indices) if (mask[i]) pixels[i] = bgColor
        bmp.setPixels(pixels, 0, w, x0, y0, w, h)
        return painted
    }

    /**
     * 算 region 的 clamped AABB(已外扩 [pad])。corners 缺失或框面积 0 返回 null。
     */
    private fun regionAabb(region: OcrTextRegion, W: Int, H: Int, pad: Int): IntArray? {
        val corners = region.corners
        if (corners.size < 4) return null
        val xs = corners.map { it.first }
        val ys = corners.map { it.second }
        val x0 = (xs.min().toInt() - pad).coerceIn(0, W - 1)
        val y0 = (ys.min().toInt() - pad).coerceIn(0, H - 1)
        val x1 = (xs.max().toInt() + pad).coerceIn(0, W - 1)
        val y1 = (ys.max().toInt() + pad).coerceIn(0, H - 1)
        return intArrayOf(x0, y0, x1, y1)
    }

    /** 参考分辨率下的像素常量按 [pxScale] 放大,至少 1px。 */
    private fun scaledPx(px: Int, pxScale: Float): Int = (px * pxScale).roundToInt().coerceAtLeast(1)

    /** 一遍 8 邻域膨胀,边界外当 false 处理。 */
    private fun dilate8(src: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            val rowStart = y * w
            for (x in 0 until w) {
                val idx = rowStart + x
                if (src[idx]) { out[idx] = true; continue }
                // 任一 8 邻居为 true → 自己变 true
                val yMin = if (y > 0) y - 1 else y
                val yMax = if (y < h - 1) y + 1 else y
                val xMin = if (x > 0) x - 1 else x
                val xMax = if (x < w - 1) x + 1 else x
                var hit = false
                outer@ for (yy in yMin..yMax) {
                    val rr = yy * w
                    for (xx in xMin..xMax) {
                        if (src[rr + xx]) { hit = true; break@outer }
                    }
                }
                if (hit) out[idx] = true
            }
        }
        return out
    }

    /**
     * Sample the dominant background color around a text region by reading pixels
     * along the border just outside the bounding box.
     *
     * For typical manga speech bubbles this returns white/light gray.
     *
     * `internal` 给 [BubbleAreaFinder] 复用 — 同一份采样逻辑保证擦除色和扩展判定色一致。
     */
    internal fun sampleBackgroundColor(bitmap: Bitmap, region: OcrTextRegion, pxScale: Float = 1f): Int {
        val corners = region.corners
        if (corners.size < 4) return Color.WHITE

        val xs = corners.map { it.first }
        val ys = corners.map { it.second }
        val minX = xs.min().toInt()
        val maxX = xs.max().toInt()
        val minY = ys.min().toInt()
        val maxY = ys.max().toInt()

        val sampleMargin = scaledPx(BG_SAMPLE_MARGIN_PX, pxScale)
        val sampleStep = scaledPx(BG_SAMPLE_STEP_PX, pxScale)
        val w = bitmap.width
        val h = bitmap.height

        val pixels = mutableListOf<Int>()

        // Sample border pixels around the region (outside the text area)
        // Top edge
        val topY = (minY - sampleMargin).coerceIn(0, h - 1)
        for (x in minX..maxX step sampleStep) {
            val sx = x.coerceIn(0, w - 1)
            pixels.add(bitmap.getPixel(sx, topY))
        }
        // Bottom edge
        val bottomY = (maxY + sampleMargin).coerceIn(0, h - 1)
        for (x in minX..maxX step sampleStep) {
            val sx = x.coerceIn(0, w - 1)
            pixels.add(bitmap.getPixel(sx, bottomY))
        }
        // Left edge
        val leftX = (minX - sampleMargin).coerceIn(0, w - 1)
        for (y in minY..maxY step sampleStep) {
            val sy = y.coerceIn(0, h - 1)
            pixels.add(bitmap.getPixel(leftX, sy))
        }
        // Right edge
        val rightX = (maxX + sampleMargin).coerceIn(0, w - 1)
        for (y in minY..maxY step sampleStep) {
            val sy = y.coerceIn(0, h - 1)
            pixels.add(bitmap.getPixel(rightX, sy))
        }

        if (pixels.isEmpty()) return Color.WHITE

        // Find the most common color (mode) via simple bucketing
        val colorCounts = mutableMapOf<Int, Int>()
        for (pixel in pixels) {
            // Quantize to reduce noise: round each channel to nearest 8
            val quantized = quantizeColor(pixel)
            colorCounts[quantized] = (colorCounts[quantized] ?: 0) + 1
        }
        val dominantQuantized = colorCounts.maxByOrNull { it.value }?.key ?: Color.WHITE

        // Return the average of all pixels matching the dominant quantized color
        val matchingPixels = pixels.filter { quantizeColor(it) == dominantQuantized }
        return averageColor(matchingPixels)
    }

    private fun quantizeColor(color: Int): Int {
        val r = (Color.red(color) / 16) * 16
        val g = (Color.green(color) / 16) * 16
        val b = (Color.blue(color) / 16) * 16
        return Color.rgb(r, g, b)
    }

    private fun averageColor(pixels: List<Int>): Int {
        if (pixels.isEmpty()) return Color.WHITE
        var rSum = 0L; var gSum = 0L; var bSum = 0L
        for (p in pixels) {
            rSum += Color.red(p)
            gSum += Color.green(p)
            bSum += Color.blue(p)
        }
        val n = pixels.size
        return Color.rgb((rSum / n).toInt(), (gSum / n).toInt(), (bSum / n).toInt())
    }
}
