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
                "edge" -> getString(R.string.translator_provider_microsoft).lineSequence().firstOrNull()?.trim()
                PreferenceList.VIETPHRASE_PROVIDER -> getString(R.string.translator_provider_vietphrase).lineSequence().firstOrNull()?.trim()
                else -> getString(R.string.translator_provider_google).lineSequence().firstOrNull()?.trim()
            }
        }
    }

    private fun requestCacheClearForProviderChange() {
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

    private fun sortListPreferenceByEntries(preferenceKey: String) {
        val preference = findPreference<ListPreference?>(preferenceKey)
        if (preference == null || preference.entries == null || preference.entryValues == null) {
            Log.w("AllTrans", "Cannot sort ListPreference: $preferenceKey")
            return
        }

        val entries = preference.entries
        val entryValues = preference.entryValues

        if (entries.size != entryValues.size) {
            Log.e("AllTrans", "Mismatch between entries and entryValues for: $preferenceKey")
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

    private fun validateListPreferenceValue(listPreference: ListPreference?) {
        if (listPreference == null) return
        val currentValue: CharSequence? = listPreference.value
        val entryValues = listPreference.entryValues
        if (currentValue != null && entryValues != null) {
            val found = entryValues.any { it == currentValue }
            if (!found && entryValues.isNotEmpty()) {
                listPreference.setValueIndex(0)
                Log.w("AllTrans", "Resetting invalid value for preference: ${listPreference.key}")
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

        fun updateLanguageLists(provider: String?) {
            if (provider == PreferenceList.VIETPHRASE_PROVIDER) {
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
                return
            }

            val (namesRes, codesRes) = when (provider) {
                "edge" -> Pair(R.array.languageNames, R.array.languageCodes)
                else -> Pair(R.array.languageNamesGoogle, R.array.languageCodesGoogle)
            }

            translateFromLanguage?.apply {
                entries = resources.getTextArray(namesRes)
                entryValues = resources.getTextArray(codesRes)
                sortListPreferenceByEntries(KEY_TRANSLATE_FROM)
                validateListPreferenceValue(this)
                isEnabled = true
            }
            translateToLanguage?.apply {
                entries = resources.getTextArray(namesRes)
                entryValues = resources.getTextArray(codesRes)
                sortListPreferenceByEntries(KEY_TRANSLATE_TO)
                validateListPreferenceValue(this)
                isEnabled = true
            }
        }

        translatorProvider?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { preference, newValue ->
            val newProvider = newValue as String?
            val oldProvider = (preference as? ListPreference)?.value
            updateLanguageLists(newProvider)
            if (oldProvider != newProvider) {
                requestCacheClearForProviderChange()
                Toast.makeText(requireContext(), R.string.clear_cache_provider, Toast.LENGTH_SHORT).show()
            }
            true
        }

        updateLanguageLists(translatorProvider?.value)
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
