package ceui.pixiv.ui.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class MangaLanguageTest {
    @Test fun `manga always targets Chinese but respects traditional UI`() {
        assertEquals("zh-CN", mangaTargetLanguage("ja"))
        assertEquals("zh-CN", mangaTargetLanguage("en"))
        assertEquals("zh-CN", mangaTargetLanguage("zh-CN"))
        assertEquals("zh-TW", mangaTargetLanguage("zh-TW"))
        assertEquals("zh-TW", mangaTargetLanguage("zh-Hant"))
    }

    @Test fun `source choices map to explicit language codes`() {
        assertEquals(MangaSourceLanguage.KOREAN, MangaSourceLanguage.fromCode("ko"))
        assertEquals(MangaSourceLanguage.AUTO, MangaSourceLanguage.fromCode("unknown"))
        assertEquals(MangaSourceLanguage.KOREAN, mangaSourceOfText("안녕하세요", MangaSourceLanguage.JAPANESE))
        assertEquals(MangaSourceLanguage.ENGLISH, mangaSourceOfText("Hello", MangaSourceLanguage.JAPANESE))
        assertEquals(MangaSourceLanguage.JAPANESE, mangaSourceOfText("こんにちは", MangaSourceLanguage.ENGLISH))
    }
}
