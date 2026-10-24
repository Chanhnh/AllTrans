package chanhnh.alltrans

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import java.text.Collator
import java.util.TreeMap

class LocalPreferenceFragment : PreferenceFragmentCompat() {
    var applicationInfo: ApplicationInfo? = null
    private var globalSettings: SharedPreferences? = null

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
                    Log.e("AllTrans", "LocalPref: Unsupported translator provider: ${listPreference.value}")
                    null
                }
            }
        }
    }

    private fun sortListPreferenceByEntries(preference: ListPreference?) {
        preference ?: return
        val entries = preference.entries ?: return
        val entryValues = preference.entryValues ?: return

        if (entries.size != entryValues.size) {
            Log.e("AllTrans", "LocalPref: Mismatch entries/entryValues for: ${preference.key}")
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
                "LocalPref: Resetting unsupported language for ${preference.key}: $currentValue -> $defaultLanguage"
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
        translateToLanguage: ListPreference?,
        enabled: Boolean
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
                    enabled
                )
                applyLanguagePreference(
                    translateToLanguage,
                    R.array.languageNamesGoogle,
                    R.array.languageCodesGoogle,
                    PreferenceList.DEFAULT_TARGET_LANGUAGE,
                    enabled
                )
            }

            "edge" -> {
                applyLanguagePreference(
                    translateFromLanguage,
                    R.array.languageNames,
                    R.array.languageCodes,
                    PreferenceList.DEFAULT_SOURCE_LANGUAGE,
                    enabled
                )
                applyLanguagePreference(
                    translateToLanguage,
                    R.array.languageNames,
                    R.array.languageCodes,
                    PreferenceList.DEFAULT_TARGET_LANGUAGE,
                    enabled
                )
            }

            else -> {
                Log.e("AllTrans", "LocalPref: Unsupported translator provider: $provider")
                translateFromLanguage?.isEnabled = false
                translateToLanguage?.isEnabled = false
            }
        }
    }

    private fun enableLocalPrefs(enable: Boolean) {
        val keysToToggle = listOf(
            "TranslateFromLanguage", "TranslateToLanguage", "SetText",
            "SetHint", "LoadURL", "Notif", "Cache", "Scroll", "Delay", "DelayWebView"
        )
        keysToToggle.forEach { key ->
            findPreference<Preference>(key)?.isEnabled = enable
        }
    }

    private fun requestCacheClear(localPrefs: SharedPreferences?, packageName: String?) {
        localPrefs?.edit()
            ?.putString("ClearCacheTime", System.currentTimeMillis().toString())
            ?.apply()
        PreferenceManager.clearCache(packageName)
    }

    override fun onCreatePreferences(bundle: Bundle?, rootKey: String?) {
        globalSettings = requireActivity().getSharedPreferences("AllTransPref", Context.MODE_PRIVATE)

        if (applicationInfo == null) {
            val safeContext = context
            if (safeContext != null) {
                Toast.makeText(safeContext, R.string.wut_why_null, Toast.LENGTH_SHORT).show()
            }
            Log.e("AllTrans", "LocalPreferenceFragment: applicationInfo is null!")
            parentFragmentManager.popBackStack()
            return
        }

        val prefManager = preferenceManager
        prefManager.sharedPreferencesName = applicationInfo!!.packageName
        val localPrefs = prefManager.sharedPreferences

        addPreferencesFromResource(R.xml.perappprefs)

        val isGloballyEnabled = globalSettings?.contains(applicationInfo!!.packageName) ?: false
        val overrideEnabled = localPrefs?.getBoolean("OverRide", false) ?: false

        findPreference<SwitchPreference>("LocalEnabled")?.isChecked = isGloballyEnabled
        findPreference<SwitchPreference>("OverRide")?.isChecked = overrideEnabled

        enableLocalPrefs(overrideEnabled)

        val translatorProvider = findPreference<ListPreference>("TranslatorProvider")
        val translateFromLanguage = findPreference<ListPreference>("TranslateFromLanguage")
        val translateToLanguage = findPreference<ListPreference>("TranslateToLanguage")

        configureTranslatorProviderPreference(translatorProvider)

        fun refreshLanguageLists() {
            val localOverrideEnabled = findPreference<SwitchPreference>("OverRide")?.isChecked == true
            updateLanguageLists(
                translatorProvider?.value,
                translateFromLanguage,
                translateToLanguage,
                localOverrideEnabled
            )
        }

        translatorProvider?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { preference, newValue ->
            val newProvider = newValue as? String
            val oldProvider = (preference as? ListPreference)?.value
            updateLanguageLists(
                newProvider,
                translateFromLanguage,
                translateToLanguage,
                findPreference<SwitchPreference>("OverRide")?.isChecked == true
            )
            if (oldProvider != newProvider) {
                requestCacheClear(localPrefs, applicationInfo?.packageName)
                Toast.makeText(requireContext(), R.string.clear_cache_provider, Toast.LENGTH_SHORT).show()
            }
            true
        }

        refreshLanguageLists()

        findPreference<SwitchPreference>("OverRide")?.setOnPreferenceChangeListener { _, newValue ->
            enableLocalPrefs(newValue as Boolean)
            refreshLanguageLists()
            true
        }

        findPreference<Preference>("ClearCache")?.setOnPreferenceClickListener { preference ->
            localPrefs?.edit()
                ?.putString("ClearCacheTime", System.currentTimeMillis().toString())
                ?.apply()
            Toast.makeText(preference.context, R.string.clear_cache_success, Toast.LENGTH_SHORT).show()
            true
        }

        translateFromLanguage?.setOnPreferenceChangeListener { _, _ ->
            requestCacheClear(localPrefs, applicationInfo?.packageName)
            true
        }
        translateToLanguage?.setOnPreferenceChangeListener { _, _ ->
            requestCacheClear(localPrefs, applicationInfo?.packageName)
            true
        }

        findPreference<SwitchPreference>("LocalEnabled")?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            globalSettings?.edit()?.apply {
                if (enabled) {
                    putBoolean(applicationInfo!!.packageName, true)
                    Utils.debugLog("LocalPref: Added ${applicationInfo!!.packageName} to global list.")
                } else {
                    remove(applicationInfo!!.packageName)
                    Utils.debugLog("LocalPref: Removed ${applicationInfo!!.packageName} from global list.")
                }
                apply()
            }
            true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
    }
}
