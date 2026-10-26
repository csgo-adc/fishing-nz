package nz.fishingnz.app.data

import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import android.util.Log
import nz.fishingnz.app.BuildConfig
import androidx.compose.runtime.mutableStateOf
import nz.fishingnz.app.model.Translator
import org.json.JSONObject

/** The app language: the phone's by default, or one chosen in Settings → Language (docs/languages.md). */
object I18n {
    data class Language(val code: String, val name: String)

    /** "Use phone language". */
    const val PHONE = ""
    val languages = listOf(
        Language("en", "English"), Language("zh-Hans", "简体中文"), Language("zh-Hant", "繁體中文"),
        Language("ko", "한국어"), Language("hi", "हिन्दी"),
    )
    private const val PREFS = "language"
    private val translators = HashMap<String, Translator>()
    // A Compose state, so every screen shows the new language as soon as it is chosen.
    private val current = mutableStateOf(Translator(Translator.ENGLISH, emptyMap()))
    @Volatile private var appContext: Context? = null

    /** The language text is shown in now: "en", "zh-Hans", "zh-Hant", "ko" or "hi". */
    val language: String get() = current.value.language

    fun t(text: String): String = current.value.translate(text)

    /** Call when the app, a widget or a background job starts, and again after the phone's language changes. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        val code = resolve(context.applicationContext)
        if (code != current.value.language) current.value = translator(context.applicationContext, code)
    }

    /** Settings → Language: [PHONE] or a language code. */
    fun choice(context: Context): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.getSystemService(LocaleManager::class.java)?.applicationLocales?.takeUnless { it.isEmpty }
            ?.let { Translator.languageFor(it.toLanguageTags().split(',')) } ?: PHONE
    } else context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("choice", PHONE) ?: PHONE

    fun choose(context: Context, code: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("choice", code).apply()
        // Android 13 and later keep a per-app language in the phone's own settings too, so both places agree.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (code == PHONE) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(code)
        }
        Analytics.track("language_changed", "language" to code.ifEmpty { "phone" })
        initialize(context)
    }

    private fun phoneTags(): List<String> = Resources.getSystem().configuration.locales.toLanguageTags().split(',')

    private fun resolve(context: Context): String {
        val chosen = choice(context)
        return if (chosen != PHONE) chosen else Translator.languageFor(phoneTags())
    }

    private fun translator(context: Context, code: String): Translator = synchronized(translators) {
        translators.getOrPut(code) {
            val entries = if (code == Translator.ENGLISH) emptyMap() else runCatching {
                val json = JSONObject(context.assets.open("strings.$code.json").bufferedReader().use { it.readText() })
                json.keys().asSequence().associateWith { json.getString(it) }
            }.getOrDefault(emptyMap())
            Translator(code, entries, onMiss = if (BuildConfig.DEBUG) { text -> Log.d("I18nMiss", text) } else null)
        }
    }
}

/** The text in the app's language. Screens get this through `ui/Text`; other places (notifications, widgets, share text) call it. */
fun tr(text: String): String = I18n.t(text)
