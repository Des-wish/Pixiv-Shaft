package ceui.pixiv.ui.translate

import android.graphics.Bitmap
import android.graphics.Color
import ceui.pixiv.ui.upscale.OcrTextRegion
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MangaEraseSafetyTest {
    @Test fun `threshold fallback preserves art when most pixels look like ink`() {
        val image = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888)
        for (y in 0 until 60) for (x in 0 until 60)
            image.setPixel(x, y, if ((x + y) % 2 == 0) Color.BLACK else Color.WHITE)
        val before = IntArray(60 * 60)
        image.getPixels(before, 0, 60, 0, 0, 60, 60)
        val region = OcrTextRegion("text", 30f, 30f, 30f, 30f, 0f, 0, 1f,
            listOf(15f to 15f, 45f to 15f, 45f to 45f, 15f to 45f))
        assertEquals(0, TextEraser.eraseText(image, listOf(region), null))
        val after = IntArray(60 * 60)
        image.getPixels(after, 0, 60, 0, 0, 60, 60)
        assertEquals(before.toList(), after.toList())
    }
}
