package io.github.yuriyurin.fit3companion.protocol

import java.util.Locale

/** Language resources present in the extracted R390 firmware, with official LocaleUtils IDs.
 * Font resource tl corresponds to the plugin's fil (Filipino); in/iw are Android's old id/he codes.
 */
object Fit3Languages {
    const val APP = "app"
    val ids = linkedMapOf(
        "ar" to 0, "az-AZ" to 2, "be-BY" to 92, "bg" to 4,
        "bn-BD" to 79, "bn-IN" to 93, "bs" to 7, "ca" to 8,
        "cs" to 9, "da" to 10, "de" to 11, "el" to 12,
        "en" to 13, "en-CA" to 77, "en-PH" to 78, "en-US" to 100,
        "es-ES" to 14, "es-US" to 83, "et-EE" to 101, "eu-ES" to 91,
        "fa" to 17, "fi" to 18, "fr" to 20, "fr-CA" to 84,
        "ga" to 21, "gl-ES" to 104, "hi" to 24, "hr" to 25,
        "hu" to 26, "hy-AM" to 90, "id" to 28, "is-IS" to 29,
        "it" to 30, "he" to 31, "ja" to 32, "ka-GE" to 105,
        "kk-KZ" to 34, "ko" to 37, "ky-KG" to 38, "lt" to 40,
        "lv" to 41, "mk-MK" to 43, "mn-MN" to 45, "mr-IN" to 46,
        "ms-MY" to 47, "nb" to 49, "nl" to 51, "pl" to 54,
        "pt-BR" to 82, "pt-PT" to 55, "ro" to 56, "ru" to 57,
        "sk" to 59, "sl" to 60, "sq-AL" to 61, "sr" to 62,
        "sv" to 63, "tg" to 87, "th" to 68, "tk" to 88,
        "fil" to 19, "tr" to 69, "uk" to 71, "ur-PK" to 72,
        "uz-UZ" to 89, "vi" to 73, "zh-CN" to 96, "zh-HK" to 80,
        "zh-TW" to 81,
    )

    fun isSelection(value: String) = value == APP || value in ids
    fun localeId(selection: String, appLanguage: String): Int {
        require(isSelection(selection))
        return ids.getValue(if (selection == APP) if (appLanguage == "ru") "ru" else "en" else selection)
    }

    fun nativeName(tag: String): String = Locale.forLanguageTag(tag).let { it.getDisplayName(it) }
    fun languagePacket(localeId: Int): ByteArray =
        SaMessageCodec.fixedRequest(Fit3SettingsCodec.MSG_LANGUAGE, SaMessageCodec.shortParam(4, localeId))
}
