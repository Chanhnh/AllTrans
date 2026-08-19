package chanhnh.alltrans

import android.content.Context
import android.util.Log
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

internal object VietPhraseTranslator {
    private const val TAG = "AllTrans:VietPhrase"
    private const val ASSET_DIR = "vietphrase"
    private const val NAMES_FILE = "Names.txt"
    private const val VIETPHRASE_FILE = "VietPhrase.txt"
    private const val PHIEN_AM_FILE = "ChinesePhienAmWords.txt"

    private val lock = Any()
    @Volatile
    private var dictionary: Dictionary? = null

    fun translate(context: Context, text: String): String {
        if (text.isEmpty()) return text
        val loadedDictionary = getOrLoadDictionary(context.applicationContext) ?: return text
        return loadedDictionary.translate(text)
    }

    private fun getOrLoadDictionary(context: Context): Dictionary? {
        dictionary?.let { return it }

        synchronized(lock) {
            dictionary?.let { return it }
            val loaded = Dictionary().apply { load(context) }
            return if (loaded.hasData()) {
                dictionary = loaded
                loaded
            } else {
                Log.w(TAG, "VietPhrase dictionaries are unavailable or empty. Returning original text.")
                null
            }
        }
    }

    private class TrieNode {
        val children = ConcurrentHashMap<Char, TrieNode>()
        var isEndOfWord = false
        var translation: String? = null
    }

    private class Dictionary {
        private val root = TrieNode()
        private val phienAmDictionary = HashMap<String, String>()
        private var loadedEntries = 0

        fun hasData(): Boolean = loadedEntries > 0 || phienAmDictionary.isNotEmpty()

        fun load(context: Context) {
            readDictionaryFile(context, NAMES_FILE, ::insert)
            readDictionaryFile(context, VIETPHRASE_FILE, ::insert)
            readDictionaryFile(context, PHIEN_AM_FILE) { key, value ->
                phienAmDictionary[key] = value
            }
            Utils.debugLog("$TAG: Loaded VietPhrase dictionaries with $loadedEntries trie entries and ${phienAmDictionary.size} phien-am entries.")
        }

        private fun readDictionaryFile(context: Context, fileName: String, processLine: (String, String) -> Unit) {
            val assetPath = "$ASSET_DIR/$fileName"
            try {
                context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { rawLine ->
                        val separatorIndex = rawLine.indexOf('=')
                        if (separatorIndex <= 0 || separatorIndex >= rawLine.length - 1) {
                            return@forEach
                        }

                        val key = rawLine.substring(0, separatorIndex).trim()
                        val value = rawLine.substring(separatorIndex + 1).trim()
                        if (key.isNotEmpty() && value.isNotEmpty()) {
                            processLine(key, value)
                        }
                    }
                }
            } catch (e: FileNotFoundException) {
                Log.w(TAG, "Missing VietPhrase asset: $assetPath")
            } catch (e: Exception) {
                Log.e(TAG, "Error reading VietPhrase asset: $assetPath", e)
            }
        }

        private fun insert(key: String, value: String) {
            var node = root
            for (char in key) {
                node = node.children.getOrPut(char) { TrieNode() }
            }
            node.isEndOfWord = true
            node.translation = value
            loadedEntries++
        }

        private fun search(key: String): String? {
            var node = root
            for (char in key) {
                node = node.children[char] ?: return null
            }
            return if (node.isEndOfWord) node.translation else null
        }

        fun translate(text: String): String {
            val normalized = convertPunctuation(text)
            val translatedTokens = tokenize(normalized)
                .filter { token -> token !in setOf("的", "了", "著") }
                .map { token ->
                    val dictionaryValue = search(token)
                    val translated = dictionaryValue?.split('/', '|')?.firstOrNull()?.trim()
                    translated ?: token
                }
                .map { token -> phienAmDictionary[token] ?: token }

            return processText(translatedTokens.joinToString(" "))
        }

        private fun tokenize(text: String): List<String> {
            val output = ArrayList<String>()
            var currentIndex = 0

            while (currentIndex < text.length) {
                var currentNode = root
                var lastFoundIndex = -1

                for (i in currentIndex until text.length) {
                    val char = text[i]
                    val nextNode = currentNode.children[char] ?: break
                    currentNode = nextNode
                    if (currentNode.isEndOfWord) {
                        lastFoundIndex = i
                    }
                }

                if (lastFoundIndex != -1) {
                    output.add(text.substring(currentIndex, lastFoundIndex + 1))
                    currentIndex = lastFoundIndex + 1
                    continue
                }

                if (isChineseCharacter(text[currentIndex])) {
                    output.add(text[currentIndex].toString())
                    currentIndex++
                    continue
                }

                val builder = StringBuilder()
                builder.append(text[currentIndex])
                while (currentIndex + 1 < text.length && !isChineseCharacter(text[currentIndex + 1])) {
                    currentIndex++
                    builder.append(text[currentIndex])
                }
                output.add(builder.toString())
                currentIndex++
            }

            return output
        }

        private fun isChineseCharacter(char: Char): Boolean {
            return char.code in 0x4E00..0x9FFF || char == '\n'
        }

        private fun convertPunctuation(text: String): String {
            val mapping = mapOf(
                '。' to ".",
                '，' to ",",
                '、' to ",",
                '；' to ";",
                '！' to "!",
                '？' to "?",
                '：' to ":",
                '（' to "(",
                '）' to ")",
                '〔' to "[",
                '〕' to "]",
                '【' to "[",
                '】' to "]",
                '｛' to "{",
                '｝' to "}",
                '『' to "“",
                '』' to "”",
                '～' to "~",
                '〖' to "[",
                '〗' to "]",
                '〘' to "[",
                '〙' to "]",
                '〚' to "[",
                '〛' to "]",
                '　' to " "
            )

            return buildString(text.length) {
                text.forEach { char ->
                    append(mapping[char] ?: char.toString())
                }
            }
        }

        private fun processText(text: String): String {
            val collapsedLines = text
                .split("\n")
                .joinToString("\n") { line -> line.trim() }

            val normalized = collapsedLines
                .replace(Regex(" +([,.?!\\]\\>:};)])"), "$1 ")
                .replace(Regex(" +([”’])"), "$1")
                .replace(Regex("([<\\[(“‘{]) +"), " $1")
                .replace(Regex(" +"), " ")

            return Regex("(^\\s*|[“‘”’.!?\\[-]\\s*)(\\p{Ll})", setOf(RegexOption.MULTILINE))
                .replace(normalized) { match ->
                    match.groupValues[1] + match.groupValues[2].uppercase()
                }
                .trim()
        }
    }
}
