package chanhnh.alltrans

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import java.text.Collator
import java.util.TreeMap

class GlobalPreferencesFragment : PreferenceFragmentCompat() {
    private fun configureTranslatorProviderPreference(preference: ListPreference?) {
        preference ?: return
        preference.entries = resources.getTextArray(R.array.translatorProviderNames)
        preference.summaryProvider = Preference.SummaryProvider<ListPreference> { listPreference ->
            when (listPreference.value) {
                "g" -> getString(R.string.translator_provider_google).lineSequence().firstOrNull()?.trim()
                "edge" -> getString(R.string.translator_provider_microsoft).lineSequence().firstOrNull()?.trim()
                PreferenceList.VIETPHRASE_PROVIDER ->
                    getString(R.string.translator_provider_vietphrase).lineSequence().firstOrNull()?.trim()
                else -> {
                    Log.e("AllTrans", "Unsupported translator provider: ${listPreference.value}")
                    null
                }
            }
        }
    }

    private fun requestCacheClear() {
        val globalPrefs = preferenceManager.sharedPreferences ?: return
        val clearTimestamp = System.currentTimeMillis().toString()

        for ((key, value) in globalPrefs.all) {
            if (key in GLOBAL_PREFERENCE_KEYS) continue
            if (value == true) {
                requireContext()
                    .getSharedPreferences(key, android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CLEAR_CACHE_TIME, clearTimestamp)
                    .apply()
                PreferenceManager.clearCache(key)
            }
        }

        PreferenceManager.clearCache()
    }

    private fun sortListPreferenceByEntries(preference: ListPreference?) {
        preference ?: return
        val entries = preference.entries ?: return
        val entryValues = preference.entryValues ?: return

        if (entries.size != entryValues.size) {
            Log.e("AllTrans", "Mismatch between entries and entryValues for: ${preference.key}")
            return
        }

        val sortRules = Collator.getInstance(resources.configuration.locales.get(0))
        sortRules.strength = Collator.PRIMARY
        val sorter = TreeMap<CharSequence?, CharSequence?>(sortRules)
        for (i in entries.indices) {
            if (!sorter.containsKey(entries[i])) {
                sorter.put(entries[i], entryValues[i])
            }
        }

        val sortedLabels = arrayOfNulls<CharSequence>(sorter.size)
        val sortedValues = arrayOfNulls<CharSequence>(sorter.size)
        var i = 0
        for (entry in sorter.entries) {
            sortedLabels[i] = entry.key
            sortedValues[i] = entry.value
            i++
        }

        val currentValue = preference.value
        preference.entries = sortedLabels
        preference.entryValues = sortedValues
        preference.value = currentValue
    }

    private fun applyLanguagePreference(
        preference: ListPreference?,
        namesRes: Int,
        codesRes: Int,
        defaultLanguage: String,
        enabled: Boolean
    ) {
        preference ?: return
        preference.entries = resources.getTextArray(namesRes)
        preference.entryValues = resources.getTextArray(codesRes)
        sortListPreferenceByEntries(preference)

        val currentValue = preference.value
        val entryValues = preference.entryValues
        if (currentValue == null || entryValues == null || entryValues.none { it == currentValue }) {
            Log.w(
                "AllTrans",
                "Resetting unsupported language for ${preference.key}: $currentValue -> $defaultLanguage"
            )
            preference.value = defaultLanguage
        }
        preference.isEnabled = enabled
    }

    private fun applyVietPhraseLanguages(
        translateFromLanguage: ListPreference?,
        translateToLanguage: ListPreference?
    ) {
        translateFromLanguage?.apply {
            entries = arrayOf(getText(R.string.Chinese_google))
            entryValues = arrayOf(PreferenceList.VIETPHRASE_SOURCE_LANGUAGE)
            value = PreferenceList.VIETPHRASE_SOURCE_LANGUAGE
            isEnabled = false
        }
        translateToLanguage?.apply {
            entries = arrayOf(getText(R.string.Vietnamese_google))
            entryValues = arrayOf(PreferenceList.VIETPHRASE_TARGET_LANGUAGE)
            value = PreferenceList.VIETPHRASE_TARGET_LANGUAGE
            isEnabled = false
        }
    }

    private fun updateLanguageLists(
        provider: String?,
        translateFromLanguage: ListPreference?,
        translateToLanguage: ListPreference?
    ) {
        when (provider) {
            PreferenceList.VIETPHRASE_PROVIDER -> {
                applyVietPhraseLanguages(translateFromLanguage, translateToLanguage)
            }

            "g" -> {
                applyLanguagePreference(
                    translateFromLanguage,
                    R.array.languageNamesGoogle,
                    R.array.languageCodesGoogle,
                    PreferenceList.DEFAULT_SOURCE_LANGUAGE,
                    true
                )
                applyLanguagePreference(
                    translateToLanguage,
                    R.array.languageNamesGoogle,
                    R.array.languageCodesGoogle,
                    PreferenceList.DEFAULT_TARGET_LANGUAGE,
                    true
                )
            }

            "edge" -> {
                applyLanguagePreference(
                    translateFromLanguage,
                    R.array.languageNames,
                    R.array.languageCodes,
                    PreferenceList.DEFAULT_SOURCE_LANGUAGE,
                    true
                )
                applyLanguagePreference(
                    translateToLanguage,
                    R.array.languageNames,
                    R.array.languageCodes,
                    PreferenceList.DEFAULT_TARGET_LANGUAGE,
                    true
                )
            }

            else -> {
                Log.e("AllTrans", "Unsupported translator provider: $provider")
                translateFromLanguage?.isEnabled = false
                translateToLanguage?.isEnabled = false
            }
        }
    }

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        val preferenceManager = preferenceManager
        preferenceManager.sharedPreferencesName = "AllTransPref"
        addPreferencesFromResource(R.xml.preferences)

        val translatorProvider = findPreference<ListPreference?>("TranslatorProvider")
        val translateFromLanguage = findPreference<ListPreference?>(KEY_TRANSLATE_FROM)
        val translateToLanguage = findPreference<ListPreference?>(KEY_TRANSLATE_TO)

        configureTranslatorProviderPreference(translatorProvider)

        translatorProvider?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { preference, newValue ->
            val newProvider = newValue as? String
            val oldProvider = (preference as? ListPreference)?.value
            updateLanguageLists(newProvider, translateFromLanguage, translateToLanguage)
            if (oldProvider != newProvider) {
                requestCacheClear()
                Toast.makeText(requireContext(), R.string.clear_cache_provider, Toast.LENGTH_SHORT).show()
            }
            true
        }

        updateLanguageLists(translatorProvider?.value, translateFromLanguage, translateToLanguage)

        translateFromLanguage?.setOnPreferenceChangeListener { _, _ ->
            requestCacheClear()
            true
        }
        translateToLanguage?.setOnPreferenceChangeListener { _, _ ->
            requestCacheClear()
            true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val KEY_CLEAR_CACHE_TIME = "ClearCacheTime"
        private const val KEY_TRANSLATE_FROM = "TranslateFromLanguage"
        private const val KEY_TRANSLATE_TO = "TranslateToLanguage"
        private val GLOBAL_PREFERENCE_KEYS = setOf(
            "Enabled",
            "Debug",
            "TranslatorProvider",
            KEY_TRANSLATE_FROM,
            KEY_TRANSLATE_TO,
            "SetText",
            "SetHint",
            "LoadURL",
            "Notif",
            "Cache",
            "Scroll",
            "Delay",
            "DelayWebView"
        )
    }
}
