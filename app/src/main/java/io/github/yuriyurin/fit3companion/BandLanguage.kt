package io.github.yuriyurin.fit3companion

import android.content.Context
import io.github.yuriyurin.fit3companion.protocol.Fit3Languages

object BandLanguage {
    fun selection(context: Context): String = context.getSharedPreferences("fit3_appearance", Context.MODE_PRIVATE)
        .getString("band_language", Fit3Languages.APP)?.takeIf(Fit3Languages::isSelection) ?: Fit3Languages.APP

    fun set(context: Context, value: String) {
        require(Fit3Languages.isSelection(value))
        context.getSharedPreferences("fit3_appearance", Context.MODE_PRIVATE).edit()
            .putString("band_language", value).commit()
    }

    fun localeId(context: Context): Int = Fit3Languages.localeId(selection(context), AppLanguage.locale(context).language)
}
