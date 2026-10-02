package io.github.yuriyurin.fit3companion

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** Checks compiled resources without changing the user's language or stored data. */
internal object LanguageResourcesSmoke {
    fun verify(targetContext: Context): String {
            fun resources(language: String) = targetContext.createConfigurationContext(
                Configuration(targetContext.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(language))
                }).resources
            val ru = resources("ru")
            val en = resources("en")
            val templates = en.getStringArray(R.array.legacy_text_templates)
            val keys = en.getStringArray(R.array.legacy_text_keys)
            check(templates.size == 617 && keys.size == templates.size)
            val pairs = templates.zip(keys).map { (template, key) ->
                val id = en.getIdentifier(key, "string", targetContext.packageName)
                check(id != 0)
                check(ru.getString(id) == template) { "Android escaping mismatch: $key" }
                template to en.getString(id)
            }
            val localizer = TextLocalizer(pairs)
            val argument = Regex("\\{(\\d+)\\}")
            pairs.forEach { (russian, english) ->
                val source = argument.replace(russian) { "x${it.groupValues[1]}" }
                val expected = argument.replace(english) { "x${it.groupValues[1]}" }
                check(localizer.translate(source) == expected) { "Translation mismatch: $russian" }
            }
            check(en.getString(R.string.language_title) == "Language")
            check(ru.getString(R.string.language_title) == "Язык")
            check(en.getString(R.string.language_russian) == "Русский")
            check(ru.getString(R.string.language_english) == "English")
            check(resources("fr").getString(R.string.language_title) == "Language")
            check(en.getString(R.string.activity_goals_title) == "Daily activity goals")
            check(ru.getString(R.string.activity_goals_title) == "Цели активности")
            check(en.getString(R.string.custom_faces_title) == "Installed")
            check(ru.getString(R.string.custom_faces_title) == "Установленные")
            check(en.getString(R.string.watch_faces_heading) == "On the band")
            check(ru.getString(R.string.watch_faces_heading) == "На браслете")
            check(en.getString(R.string.watch_faces_delete_action) == "Delete from the band")
            check(ru.getString(R.string.custom_faces_delete_local) == "Удалить из приложения")
            check(en.getString(R.string.custom_faces_variants) == "Variants")
            check(ru.getString(R.string.watch_faces_delete_title) == "Удалить циферблат?")
            check(en.getString(R.string.local_faces_heading) == "Saved in the app")
            val body = android.graphics.BitmapFactory.decodeResource(en, R.drawable.fit3_preview_body)
            check(body != null && body.width == 1080 && body.height == 780)
            check(android.graphics.Color.alpha(body.getPixel(500, 300)) == 0) {
                "Watchface aperture must remain transparent"
            }
            check(android.graphics.Color.alpha(body.getPixel(390, 200)) == 255) {
                "The Fit3 case must remain visible"
            }
            body.recycle()
            check(en.getString(R.string.activity_goal_summary, 90, 500) == "90 min · 500 kcal")
            check(ru.getString(R.string.activity_goal_summary, 90, 500) == "90 мин · 500 ккал")
            check(localizer.translate("Заряд 39% · зарядка") == "Battery 39% · charging")
            check(localizer.translate("5ч 14м") == "5h 14m")
            return "PASS: 617 compiled label/template pairs, Russian/English resources, unsupported-language fallback, units and nested status\n"
    }
}
