package chanhnh.alltrans

import java.text.Normalizer

/**
 * Pure translation logic for the Vietphrase provider.
 *
 * The pipeline is a Kotlin port of the userscript core: rules first, then
 * names, then the longest VietPhrase entry, and finally Hán-Việt characters
 * for anything that is not in a dictionary.
 *
 * Adapted from duongden's vietphrase-translator userscript (GPL-3.0):
 * https://github.com/duongden/script-vietphrase-translator
 */
internal class VietPhraseTranslationEngine(
    private val dictionaries: VietPhraseDictionaries,
    internal val rules: VietPhraseRuleEngine,
    private val separator: String = "/"
) {
    private val namesMaxLen = dictionaries.names.keys.maxOfOrNull { it.length } ?: 0
    private val vpMaxLen = dictionaries.vp.keys.maxOfOrNull { it.length } ?: 1

    fun translate(text: String): String {
        if (text.isEmpty()) return text
        return translateCore(text)
    }

    // ---------------------------------------------------------------------
    // Rule/name/dictionary pipeline
    // ---------------------------------------------------------------------

    private fun translateCore(text: String): String {
        var normalized = fixVietnamese(text)
        if (normalized.isBlank() || !hasHanChar(normalized)) return normalized
        normalized = normalizePunctuation(normalized)

        val ruleSegments = ArrayList<Segment>()
        if (rules.rules.isNotEmpty()) {
            var plainStart = 0
            var cursor = 0
            while (cursor < normalized.length) {
                val hit = rules.matchAt(normalized, cursor)
                if (hit == null) {
                    cursor++
                    continue
                }
                if (plainStart < cursor) {
                    ruleSegments.add(Segment(text = normalized.substring(plainStart, cursor)))
                }
                ruleSegments.add(
                    Segment(
                        text = hit.translation,
                        han = hit.source,
                        isRule = true
                    )
                )
                cursor = hit.end
                plainStart = cursor
            }
            if (plainStart < normalized.length) {
                ruleSegments.add(Segment(text = normalized.substring(plainStart)))
            }
        } else {
            ruleSegments.add(Segment(text = normalized))
        }

        val segments = ArrayList<Segment>()
        for (segment in ruleSegments) {
            if (segment.isRule) {
                segments.add(segment)
            } else {
                segments.addAll(takeNameSegments(segment.text))
            }
        }

        val pairs = ArrayList<PairToken>()
        for (segment in segments) {
            if (segment.isRule || segment.isName) {
                pairs.add(
                    PairToken(
                        han = segment.han ?: segment.text,
                        viet = segment.text,
                        isName = segment.isName
                    )
                )
                continue
            }

            val source = segment.text
            var index = 0
            while (index < source.length) {
                var matched = false
                val remaining = source.length - index
                for (length in minOf(vpMaxLen, remaining) downTo 1) {
                    val candidate = source.substring(index, index + length)
                    val value = dictionaries.vp[candidate]
                    if (value != null) {
                        val translated = firstMeaning(value, separator)
                        pairs.add(PairToken(han = candidate, viet = translated))
                        index += length
                        matched = true
                        break
                    }
                }
                if (matched) continue

                val char = source[index]
                if (!isHanChar(char)) {
                    val raw = takeNonHanRun(source, index)
                    if (raw.isNotEmpty()) {
                        pairs.add(PairToken(han = raw, viet = raw))
                    }
                    index += raw.length.coerceAtLeast(1)
                    continue
                }

                if (char in DICH_LIEU_SET) {
                    index++
                    continue
                }

                val translated = dictionaries.pa[char.toString()] ?: char.toString()
                pairs.add(PairToken(han = char.toString(), viet = translated))
                index++
            }
        }

        normalizeTokenCase(pairs)
        val joined = joinTranslatedTokens(pairs.map { it.viet })
        val processed = postProcessTranslatedText(joined)
        return fixVietnamese(resolvePlaceholders(autoCapitalize(processed)))
    }

    private fun takeNameSegments(text: String): List<Segment> {
        if (namesMaxLen == 0 || text.isEmpty()) return listOf(Segment(text = text))

        val segments = ArrayList<Segment>()
        var plainStart = 0
        var index = 0

        while (index < text.length) {
            var name = ""
            val remaining = text.length - index
            for (length in minOf(namesMaxLen, remaining) downTo 1) {
                val candidate = text.substring(index, index + length)
                if (dictionaries.names.containsKey(candidate)) {
                    name = candidate
                    break
                }
            }

            if (name.isEmpty()) {
                index++
                continue
            }

            var vpLength = 0
            for (length in minOf(vpMaxLen, remaining) downTo name.length) {
                if (dictionaries.vp.containsKey(text.substring(index, index + length))) {
                    vpLength = length
                    break
                }
            }
            if (vpLength >= name.length) {
                index++
                continue
            }

            if (plainStart < index) {
                segments.add(Segment(text = text.substring(plainStart, index)))
            }

            val nameValue = dictionaries.names[name]
                ?.let { firstMeaning(it, separator) }
                ?.takeIf { it.isNotEmpty() }
                ?: name
            segments.add(
                Segment(
                    text = nameValue,
                    han = name,
                    isName = true
                )
            )
            index += name.length
            plainStart = index
        }

        if (plainStart < text.length) {
            segments.add(Segment(text = text.substring(plainStart)))
        }
        return segments
    }

    private fun normalizeTokenCase(pairs: List<PairToken>) {
        var context = ""
        for (pair in pairs) {
            var value = pair.viet
            val startsSentence = context.isBlank() ||
                SENTENCE_END_RE.containsMatchIn(context) ||
                OPEN_MARKER_END_RE.containsMatchIn(context)

            if (!startsSentence && !pair.isName && value.isNotEmpty() && value[0].isUpperCase()) {
                value = value[0].lowercaseChar() + value.substring(1)
                pair.viet = value
            }
            context = joinTranslatedTokens(listOf(context, value))
        }
    }

    private fun joinTranslatedTokens(tokens: List<String>): String {
        var result = ""
        for (token in tokens) {
            if (token.isEmpty()) continue
            if (result.isEmpty()) {
                result = token
                continue
            }

            val last = result[result.length - 1]
            val first = token[0]

            result += when {
                first in CLOSE_CHARS -> token
                last == '\u0002' || last == '(' || last == '[' -> token
                last == ' ' -> token
                isVietLetter(last) || isVietLetter(first) -> " $token"
                last == '\u0003' || last == '\u0005' -> " $token"
                else -> token
            }
        }
        return result
    }

    private fun postProcessTranslatedText(text: String): String {
        var output = text
        output = output.replace(Regex("\\s{2,}"), " ")
        output = output.replace(Regex("\\s+([\u0003,.:;!?)»\\]])")) { it.groupValues[1] }
        output = output.replace(Regex("([\u0002(\\[])\\s+")) { it.groupValues[1] }
        output = output.replace(Regex("(\u0003)([$VIET_LETTERS\u0002(\\[])")) {
            it.groupValues[1] + " " + it.groupValues[2]
        }
        output = output.replace(Regex("([$VIET_LETTERS\u0003\\])])(\u0002)")) {
            it.groupValues[1] + " " + it.groupValues[2]
        }
        output = output.replace(Regex("([:;!?,])([$VIET_LETTERS\u0002])")) {
            it.groupValues[1] + " " + it.groupValues[2]
        }
        output = output.replace(Regex("([$VIET_LETTERS])([(\\[])")) {
            it.groupValues[1] + " " + it.groupValues[2]
        }
        output = output.replace(Regex("(^|[^0-9]),(?=[0-9])")) { it.groupValues[1] + ", " }
        return output.trim()
    }

    private fun autoCapitalize(text: String): String {
        if (text.isBlank()) return text
        var output = text.trimStart()
        if (output.isNotEmpty()) {
            output = output[0].uppercaseChar() + output.substring(1)
        }

        output = output.replace(Regex("([.!?])(\\s*)([$VIET_LOWER])")) {
            it.groupValues[1] + it.groupValues[2] + it.groupValues[3].uppercase()
        }
        output = output.replace(Regex("(:\\s+\u0002)([$VIET_LOWER])")) {
            it.groupValues[1] + it.groupValues[2].uppercase()
        }
        output = output.replace(Regex("((?:^|\\s)\u0002)([$VIET_LOWER])")) {
            it.groupValues[1] + it.groupValues[2].uppercase()
        }
        return output
    }

    private fun resolvePlaceholders(text: String): String {
        return text
            .replace('\u0002', '"')
            .replace('\u0003', '"')
            .replace('\u0004', '‘')
            .replace('\u0005', '’')
    }

    private fun normalizePunctuation(text: String): String {
        var output = text
        for ((from, to) in PUNCT_MAP) {
            if (output.contains(from)) output = output.replace(from, to)
        }
        return output
    }

    private fun takeNonHanRun(text: String, start: Int): String {
        var end = start
        while (end < text.length && !isHanChar(text[end])) end++
        return text.substring(start, end)
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun firstMeaning(value: String, vararg separators: String): String {
        var end = value.length
        for (separator in separators) {
            if (separator.isEmpty()) continue
            val at = value.indexOf(separator)
            if (at in 0 until end) end = at
        }
        return value.substring(0, end).trim()
    }

    private fun fixVietnamese(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFC)
    }

    private fun hasHanChar(text: String): Boolean = text.any(::isHanChar)

    private fun isHanChar(char: Char): Boolean {
        return char.code in 0x3400..0x4DBF ||
            char.code in 0x4E00..0x9FFF ||
            char.code in 0xF900..0xFAFF ||
            char.code == 0x3007
    }

    private fun isVietLetter(char: Char): Boolean {
        return char in 'a'..'z' || char in 'A'..'Z' || char in 'À'..'ỹ'
    }

    private data class Segment(
        val text: String,
        val han: String? = null,
        val isRule: Boolean = false,
        val isName: Boolean = false
    )

    private data class PairToken(
        val han: String,
        var viet: String,
        val isName: Boolean = false
    )

    companion object {
        private const val VIET_LETTERS = "a-zA-ZÀ-ỹ"
        private const val VIET_LOWER = "a-zàáâãèéêìíòóôõùúýăđơưạảấầẩẫậắằẳẵặẹẻẽếềểễệỉịọỏốồổỗộớờởỡợụủứừửữựỳỷỹ"

        private val DICH_LIEU_SET = setOf('的', '了', '着', '著')
        private val CLOSE_CHARS = setOf('\u0003', ',', '.', ':', ';', '!', '?', ')', '»', ']')
        private val SENTENCE_END_RE = Regex("[.!?]\\s*$")
        private val OPEN_MARKER_END_RE = Regex("\u0002\\s*$")

        private val PUNCT_MAP = listOf(
            "。》" to ".\u0003",
            "。』" to ".\u0003",
            "。」" to ".\u0003",
            "？》" to "?\u0003",
            "！》" to "!\u0003",
            "《" to "\u0002",
            "》" to "\u0003",
            "〈" to "\u0002",
            "〉" to "\u0003",
            "「" to "\u0002",
            "」" to "\u0003",
            "『" to "\u0002",
            "』" to "\u0003",
            "“" to "\u0002",
            "”" to "\u0003",
            "‘" to "\u0004",
            "’" to "\u0005",
            "【" to "[",
            "】" to "]",
            "〔" to "[",
            "〕" to "]",
            "〖" to "[",
            "〗" to "]",
            "（" to "(",
            "）" to ")",
            "｛" to "{",
            "｝" to "}",
            "。" to ".",
            "！" to "!",
            "？" to "?",
            "；" to ";",
            "：" to ":",
            "，" to ",",
            "、" to ", ",
            "……" to "...",
            "…" to "...",
            "——" to "—",
            "—" to "—",
            "－" to "-",
            "～" to "~",
            "•" to "·",
            "　" to " ",
            "／" to "/",
            "＼" to "\\",
            "！" to "!",
            "＂" to "\"",
            "＃" to "#",
            "＄" to "$",
            "％" to "%",
            "＆" to "&",
            "＇" to "'",
            "＊" to "*",
            "＋" to "+",
            "＜" to "<",
            "＝" to "=",
            "＞" to ">",
            "＠" to "@",
            "［" to "[",
            "］" to "]",
            "＾" to "^",
            "＿" to "_",
            "｀" to "`",
            "｜" to "|"
        )
    }
}
