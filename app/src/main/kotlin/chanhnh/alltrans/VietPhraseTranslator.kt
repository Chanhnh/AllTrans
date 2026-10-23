package chanhnh.alltrans

import android.content.Context
import android.util.Log
import java.io.FileNotFoundException
import java.text.Normalizer

internal object VietPhraseTranslator {
    /**
     * Bump this when the translation algorithm or bundled dictionaries change
     * in a way that invalidates previously cached translations.
     */
    const val ENGINE_VERSION = 2

    private const val TAG = "AllTrans:VietPhrase"
    private const val ASSET_DIR = "vietphrase"
    private const val NAMES_FILE = "Names.txt"
    private const val VIETPHRASE_FILE = "VietPhrase.txt"
    private const val PHIEN_AM_FILE = "ChinesePhienAmWords.txt"
    private const val PRONOUNS_FILE = "Pronouns.txt"
    private const val RULE_FILE = "rule.txt"

    private val lock = Any()

    @Volatile
    private var translationEngine: VietPhraseTranslationEngine? = null

    fun translate(
        context: Context,
        text: String,
        advanced: Boolean = PreferenceList.VietPhraseRuleEngine
    ): String {
        if (text.isEmpty()) return text
        val engine = getOrLoadEngine(context.applicationContext) ?: return text
        return engine.translate(text, advanced)
    }

    private fun getOrLoadEngine(context: Context): VietPhraseTranslationEngine? {
        translationEngine?.let { return it }

        synchronized(lock) {
            translationEngine?.let { return it }
            val loaded = load(context)
            return if (loaded != null) {
                translationEngine = loaded
                loaded
            } else {
                Log.w(TAG, "VietPhrase dictionaries are unavailable or empty. Returning original text.")
                null
            }
        }
    }

    private fun load(context: Context): VietPhraseTranslationEngine? {
        val names = HashMap<String, String>()
        val vietPhrase = HashMap<String, String>()
        val phienAm = HashMap<String, String>()
        val pronouns = HashMap<String, String>()

        readDictionaryFile(context, NAMES_FILE, names)
        readDictionaryFile(context, VIETPHRASE_FILE, vietPhrase)
        readDictionaryFile(context, PHIEN_AM_FILE, phienAm)
        readDictionaryFile(context, PRONOUNS_FILE, pronouns)
        val ruleText = readTextFile(context, RULE_FILE)

        if (names.isEmpty() && vietPhrase.isEmpty() && phienAm.isEmpty() && pronouns.isEmpty()) {
            return null
        }

        val dictionaries = VietPhraseDictionaries(
            pa = phienAm,
            vp = vietPhrase,
            names = names,
            pronouns = pronouns
        )
        val rules = VietPhraseRuleEngine.create(
            ruleText = ruleText,
            dictionaries = dictionaries,
            separator = "/",
            vpMaxLen = vietPhrase.keys.maxOfOrNull { it.length } ?: 1
        )

        Utils.debugLog(
            "$TAG: Loaded ${names.size} names, ${vietPhrase.size} vietphrase entries, " +
                "${phienAm.size} phien-am entries, ${pronouns.size} pronouns, ${rules.rules.size} rules."
        )
        if (rules.errors.isNotEmpty()) {
            Utils.debugLog("$TAG: ${rules.errors.size} invalid rule(s) ignored; first: ${rules.errors.first()}")
        }

        return VietPhraseTranslationEngine(
            dictionaries = dictionaries,
            rules = rules,
            separator = "/"
        )
    }

    private fun readDictionaryFile(context: Context, fileName: String, target: MutableMap<String, String>) {
        val assetPath = "$ASSET_DIR/$fileName"
        try {
            context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { rawLine ->
                    val line = normalizeVietnamese(rawLine.removePrefix("\uFEFF"))
                    if (line.isEmpty() || line.startsWith("//") || line.startsWith("#") || line.startsWith("=")) {
                        return@forEach
                    }
                    val separatorIndex = line.indexOf('=')
                    if (separatorIndex <= 0 || separatorIndex >= line.length - 1) {
                        return@forEach
                    }

                    val key = normalizeVietnamese(line.substring(0, separatorIndex).trim())
                    val value = normalizeVietnamese(line.substring(separatorIndex + 1).trim())
                    if (key.isNotEmpty() && value.isNotEmpty()) {
                        target[key] = value
                    }
                }
            }
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "Missing VietPhrase asset: $assetPath")
        } catch (e: Exception) {
            Log.e(TAG, "Error reading VietPhrase asset: $assetPath", e)
        }
    }

    private fun normalizeVietnamese(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFC)
    }

    private fun readTextFile(context: Context, fileName: String): String {
        val assetPath = "$ASSET_DIR/$fileName"
        return try {
            context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { reader ->
                normalizeVietnamese(reader.readText().removePrefix("\uFEFF"))
            }
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "Missing VietPhrase asset: $assetPath")
            ""
        } catch (e: Exception) {
            Log.e(TAG, "Error reading VietPhrase asset: $assetPath", e)
            ""
        }
    }
}
