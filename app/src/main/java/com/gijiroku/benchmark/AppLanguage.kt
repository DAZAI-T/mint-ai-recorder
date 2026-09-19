package com.gijiroku.benchmark

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** App-owned language preference; user content is never translated by the UI. */
object AppLanguage {
    @Volatile var code: String = "ja"
        private set

    fun initialize(context: Context) {
        code = context.getSharedPreferences("language", Context.MODE_PRIVATE).getString("code", "ja")
            ?.takeIf { it == "en" } ?: "ja"
    }

    fun save(context: Context, language: String) {
        require(language == "ja" || language == "en")
        context.getSharedPreferences("language", Context.MODE_PRIVATE).edit().putString("code", language).apply()
        code = language
    }

    fun localized(context: Context): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(code))
        return context.createConfigurationContext(configuration)
    }

    fun text(japanese: String, english: String): String = if (code == "en") english else japanese
}

class GijirokuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLanguage.initialize(this)
    }
}
