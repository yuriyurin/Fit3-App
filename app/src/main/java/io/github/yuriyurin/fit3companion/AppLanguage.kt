package io.github.yuriyurin.fit3companion

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object AppLanguage {
    const val SYSTEM = "system"
    private val supported = setOf(SYSTEM, "ru", "en")

    fun selection(context: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            return if (locales.isEmpty) SYSTEM else locales[0].language.takeIf { it in supported } ?: SYSTEM
        }
        return context.getSharedPreferences("fit3_appearance", Context.MODE_PRIVATE)
            .getString("language", SYSTEM).takeIf { it in supported } ?: SYSTEM
    }

    fun locale(context: Context): Locale {
        val selected = selection(context)
        val language = if (selected == SYSTEM) Resources.getSystem().configuration.locales[0].language else selected
        return if (language == "ru") Locale.forLanguageTag("ru") else Locale.ENGLISH
    }

    fun set(context: Context, value: String) {
        require(value in supported)
        context.getSharedPreferences("fit3_appearance", Context.MODE_PRIVATE).edit()
            .putString("language", value).commit()
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (value == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(value)
        }
        AppStrings.initialize(context)
    }

    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale(context))
        return context.createConfigurationContext(config)
    }
}

object AppStrings {
    @Volatile var locale: Locale = Locale.ENGLISH
        private set
    @Volatile private var localizer = TextLocalizer(emptyList())
    private var loaded = false

    @Synchronized fun initialize(context: Context) {
        locale = AppLanguage.locale(context)
        if (loaded) return
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        val resources = context.createConfigurationContext(config).resources
        val templates = resources.getStringArray(R.array.legacy_text_templates)
        val keys = resources.getStringArray(R.array.legacy_text_keys)
        check(templates.size == keys.size)
        localizer = TextLocalizer(templates.zip(keys).map { (template, key) ->
            val id = resources.getIdentifier(key, "string", context.packageName)
            check(id != 0) { "Missing translation: $key" }
            template to resources.getString(id)
        })
        loaded = true
    }

    fun translate(value: String): String = if (locale.language == "ru") value else localizer.translate(value)
}

class Fit3Application : Application() {
    override fun onCreate() {
        super.onCreate()
        AppStrings.initialize(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppStrings.initialize(this)
    }
}
