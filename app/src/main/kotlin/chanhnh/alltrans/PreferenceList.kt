package chanhnh.alltrans

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

internal object PreferenceList {
    const val VIETPHRASE_PROVIDER = "vietphrase"
    const val VIETPHRASE_SOURCE_LANGUAGE = "zh"
    const val VIETPHRASE_TARGET_LANGUAGE = "vi"

    const val DEFAULT_SOURCE_LANGUAGE = "auto"
    const val DEFAULT_TARGET_LANGUAGE = "vi"

    var Enabled: Boolean = false
    var LocalEnabled: Boolean = false
    var Debug: Boolean = false

    var TranslateFromLanguage: String? = null
    var TranslateToLanguage: String? = null

    var SetText: Boolean = false
    var SetHint: Boolean = false
    var ExtendedViewTranslation: Boolean = true
    var LoadURL: Boolean = false
    var Notif: Boolean = false

    var Caching: Boolean = false
    var CachingTime: Long = 0
    var Delay: Int = 0
    var DelayWebView: Int = 0
    var Scroll: Boolean = false

    var TranslatorProvider: String? = "g"

    fun isVietPhraseLanguagePair(fromLanguage: String?, toLanguage: String?): Boolean {
        return (fromLanguage == DEFAULT_SOURCE_LANGUAGE || fromLanguage == VIETPHRASE_SOURCE_LANGUAGE) &&
            toLanguage == VIETPHRASE_TARGET_LANGUAGE
    }

    private fun getValue(pref: MutableMap<String?, Any?>, key: String?, defValue: Any?): Any? {
        return if (pref.containsKey(key)) pref[key] else defValue
    }

    fun getPref(globalPref: String?, localPref: String?, packageName: String?) {
        val gPref = Gson().fromJson<MutableMap<String?, Any?>>(
            globalPref,
            object : TypeToken<MutableMap<String?, Any?>?>() {}.getType()
        ) ?: mutableMapOf()

        val lPref = Gson().fromJson<MutableMap<String?, Any?>>(
            localPref,
            object : TypeToken<MutableMap<String?, Any?>?>() {}.getType()
        ) ?: mutableMapOf()

        Enabled = getValue(gPref, "Enabled", false) as Boolean
        LocalEnabled = getValue(gPref, packageName, false) as Boolean
        Debug = getValue(gPref, "Debug", false) as Boolean

        val clearCacheTimeStr = getValue(lPref, "ClearCacheTime", "0") as String?
        CachingTime = try {
            clearCacheTimeStr?.takeIf { it.isNotBlank() }?.toLong() ?: 0L
        } catch (e: NumberFormatException) {
            Utils.debugLog("Error parsing ClearCacheTime: '$clearCacheTimeStr' for $packageName")
            0L
        }

        TranslatorProvider =
            getValue(lPref, "TranslatorProvider", getValue(gPref, "TranslatorProvider", "g")) as String?

        val useLocalSettings = getValue(lPref, "OverRide", false) as Boolean

        if (useLocalSettings) {
            Utils.debugLog("Overriding global preferences with local ones for $packageName")

            TranslateFromLanguage = getValue(
                lPref,
                "TranslateFromLanguage",
                getValue(gPref, "TranslateFromLanguage", DEFAULT_SOURCE_LANGUAGE)
            ) as String?
            TranslateToLanguage = getValue(
                lPref,
                "TranslateToLanguage",
                getValue(gPref, "TranslateToLanguage", DEFAULT_TARGET_LANGUAGE)
            ) as String?

            SetText = getValue(lPref, "SetText", getValue(gPref, "SetText", true)) as Boolean
            SetHint = getValue(lPref, "SetHint", getValue(gPref, "SetHint", true)) as Boolean
            ExtendedViewTranslation = getValue(
                lPref,
                "ExtendedViewTranslation",
                getValue(gPref, "ExtendedViewTranslation", true)
            ) as Boolean
            LoadURL = getValue(lPref, "LoadURL", getValue(gPref, "LoadURL", true)) as Boolean
            Notif = getValue(lPref, "Notif", getValue(gPref, "Notif", true)) as Boolean
            Caching = getValue(lPref, "Cache", getValue(gPref, "Cache", true)) as Boolean
            Scroll = getValue(lPref, "Scroll", getValue(gPref, "Scroll", false)) as Boolean

            Delay = (getValue(lPref, "Delay", getValue(gPref, "Delay", "0")) as String?)?.toIntOrNull() ?: 0
            DelayWebView =
                (getValue(lPref, "DelayWebView", getValue(gPref, "DelayWebView", "500")) as String?)?.toIntOrNull()
                    ?: 500
        } else {
            Utils.debugLog("Using global preferences for $packageName (OverRide is false)")

            TranslateFromLanguage =
                getValue(gPref, "TranslateFromLanguage", DEFAULT_SOURCE_LANGUAGE) as String?
            TranslateToLanguage =
                getValue(gPref, "TranslateToLanguage", DEFAULT_TARGET_LANGUAGE) as String?

            SetText = getValue(gPref, "SetText", true) as Boolean
            SetHint = getValue(gPref, "SetHint", true) as Boolean
            ExtendedViewTranslation = getValue(gPref, "ExtendedViewTranslation", true) as Boolean
            LoadURL = getValue(gPref, "LoadURL", true) as Boolean
            Notif = getValue(gPref, "Notif", true) as Boolean
            Caching = getValue(gPref, "Cache", true) as Boolean
            Scroll = getValue(gPref, "Scroll", false) as Boolean

            Delay = (getValue(gPref, "Delay", "0") as String?)?.toIntOrNull() ?: 0
            DelayWebView = (getValue(gPref, "DelayWebView", "500") as String?)?.toIntOrNull() ?: 500
        }

        if (TranslatorProvider == VIETPHRASE_PROVIDER) {
            TranslateFromLanguage = VIETPHRASE_SOURCE_LANGUAGE
            TranslateToLanguage = VIETPHRASE_TARGET_LANGUAGE
        }

        Utils.debugLog("---- Prefs loaded for $packageName ----")
        Utils.debugLog("Provider: $TranslatorProvider, From: $TranslateFromLanguage, To: $TranslateToLanguage")
        Utils.debugLog("---- End Prefs for $packageName ----")
    }
}
