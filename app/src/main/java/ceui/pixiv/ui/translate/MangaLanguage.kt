package ceui.pixiv.ui.translate

/** 原文语言可按页覆盖；AUTO 由 OCR 对每个区域独立判断。 */
enum class MangaSourceLanguage(val code: String) {
    AUTO("auto"), JAPANESE("ja"), ENGLISH("en"), KOREAN("ko");

    companion object {
        fun fromCode(code: String): MangaSourceLanguage = entries.firstOrNull { it.code == code } ?: AUTO
    }
}

/** 漫画译文只输出中文；简繁跟随应用语言，其他界面语言默认简体。 */
fun mangaTargetLanguage(appLanguage: String): String =
    if (appLanguage.equals("zh-TW", true) || appLanguage.equals("zh-Hant", true)) "zh-TW" else "zh-CN"

fun mangaSourceOfText(text: String, fallback: MangaSourceLanguage): MangaSourceLanguage = when {
    text.any { it in '\uac00'..'\ud7af' || it in '\u1100'..'\u11ff' } -> MangaSourceLanguage.KOREAN
    text.any { it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9fff' } -> MangaSourceLanguage.JAPANESE
    text.any { it in 'A'..'Z' || it in 'a'..'z' } -> MangaSourceLanguage.ENGLISH
    else -> fallback
}

fun mangaTranslator(source: MangaSourceLanguage): Translator = when {
    AiTranslator.isActive() -> AiTranslator
    source == MangaSourceLanguage.JAPANESE && CloudTranslator.isActive() -> CloudTranslator
    else -> GoogleWebTranslator
}
