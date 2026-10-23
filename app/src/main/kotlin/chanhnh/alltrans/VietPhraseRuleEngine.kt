package chanhnh.alltrans

import java.math.BigInteger

/**
 * Kotlin port of the rule engine used by the Vietphrase userscript.
 *
 * Only the dictionary/rule matching part is ported. The browser/DOM scanner,
 * userscript storage, and dictionary download code are intentionally not
 * included: Android loads the dictionaries from bundled assets instead.
 *
 * Adapted from duongden's vietphrase-translator userscript (GPL-3.0):
 * https://github.com/duongden/script-vietphrase-translator
 */
internal data class VietPhraseDictionaries(
    val pa: Map<String, String>,
    val vp: Map<String, String>,
    val names: Map<String, String>,
    val pronouns: Map<String, String>
)

internal class VietPhraseRuleEngine private constructor(
    private val dictionaries: VietPhraseDictionaries,
    private val separator: String,
    private val vpMaxLen: Int,
    val rules: List<Rule> = emptyList(),
    val errors: List<ParseError> = emptyList()
) {
    internal data class Rule(
        val pattern: String,
        val translation: String,
        val line: Int,
        val tokens: List<Token>,
        val anchor: String,
        val specificity: Int,
        val wildcardCount: Int,
        val order: Int
    )

    internal data class Match(
        val start: Int,
        val end: Int,
        val source: String,
        val translation: String,
        val pattern: String,
        val line: Int
    )

    internal data class ParseError(
        val line: Int,
        val reason: String,
        val source: String
    )

    internal enum class TokenType { LITERAL, GROUP, CAPTURE }

    internal data class Token(
        val type: TokenType,
        val literal: String = "",
        val alternatives: List<String> = emptyList(),
        val optional: Boolean = false,
        val sources: List<String> = emptyList(),
        val min: Int = 0,
        val max: Int = 0
    )

    private data class Capture(val length: Int, val raw: String, val value: String)
    private data class WalkResult(val end: Int, val captures: List<Capture>)
    private data class ParsedRules(val rules: List<Rule>, val errors: List<ParseError>)
    private data class WildcardEntry(val rule: Rule, val anchor: String, val minOffset: Int, val maxOffset: Int)

    private val direct = HashMap<Char, MutableList<Rule>>()
    private val wildcardIndex = HashMap<Char, MutableList<WildcardEntry>>()
    private val wildcardFirst = ArrayList<Rule>()
    private var maxWildcardPrefix = 0

    init {
        buildIndexes()
    }

    fun matchAt(text: String, start: Int): Match? {
        if (start < 0 || start >= text.length) return null

        val candidates = ArrayList<Rule>()
        val seen = HashSet<Int>()
        direct[text[start]]?.let { rulesAtStart ->
            for (rule in rulesAtStart) {
                if (seen.add(rule.order)) candidates.add(rule)
            }
        }

        val lookaheadEnd = minOf(text.length - start - 1, maxWildcardPrefix)
        if (lookaheadEnd >= 1) {
            for (offset in 1..lookaheadEnd) {
                val indexed = wildcardIndex[text[start + offset]] ?: continue
                for (item in indexed) {
                    if (offset < item.minOffset || offset > item.maxOffset) continue
                    if (item.rule.order in seen) continue
                    if (!text.startsWith(item.anchor, start + offset)) continue
                    seen.add(item.rule.order)
                    candidates.add(item.rule)
                }
            }
        }

        // Defensive fallback for a future wildcard-only syntax. Current
        // validation requires a literal/group anchor somewhere in the rule.
        if (wildcardIndex.isEmpty()) {
            for (rule in wildcardFirst) {
                if (seen.add(rule.order)) candidates.add(rule)
            }
        }

        candidates.sortBy { it.order }

        for (rule in candidates) {
            val hit = matchRule(rule, text, start) ?: continue
            val vpLength = longestVpAt(text, start)
            if (vpLength >= hit.end - start) continue
            return hit
        }

        return null
    }

    private fun buildIndexes() {
        for (rule in rules) {
            val first = rule.tokens.firstOrNull() ?: continue
            when {
                first.type == TokenType.LITERAL && first.literal.isNotEmpty() -> {
                    direct.getOrPut(first.literal[0]) { ArrayList() }.add(rule)
                }

                first.type == TokenType.GROUP && !first.optional -> {
                    for (alternative in first.alternatives) {
                        if (alternative.isNotEmpty()) {
                            direct.getOrPut(alternative[0]) { ArrayList() }.add(rule)
                        }
                    }
                }

                else -> {
                    wildcardFirst.add(rule)
                    var minOffset = 0
                    var maxOffset = 0
                    var anchors: List<String>? = null

                    for (token in rule.tokens) {
                        if (token.type == TokenType.LITERAL && token.literal.isNotEmpty()) {
                            anchors = listOf(token.literal)
                            break
                        }
                        if (token.type == TokenType.GROUP && !token.optional) {
                            anchors = token.alternatives
                            break
                        }
                        if (token.type == TokenType.CAPTURE) {
                            minOffset += token.min
                            maxOffset += token.max
                        } else if (token.type == TokenType.GROUP && token.optional) {
                            maxOffset += token.alternatives.maxOfOrNull { it.length } ?: 0
                        }
                    }

                    val foundAnchors = anchors ?: continue
                    maxWildcardPrefix = maxOf(maxWildcardPrefix, maxOffset)
                    for (anchor in foundAnchors) {
                        if (anchor.isEmpty()) continue
                        wildcardIndex.getOrPut(anchor[0]) { ArrayList() }
                            .add(WildcardEntry(rule, anchor, minOffset, maxOffset))
                    }
                }
            }
        }
    }

    private fun matchRule(rule: Rule, text: String, start: Int): Match? {
        fun walk(tokenIndex: Int, position: Int, captures: List<Capture>): WalkResult? {
            if (tokenIndex == rule.tokens.size) {
                return WalkResult(position, captures)
            }

            val token = rule.tokens[tokenIndex]
            when (token.type) {
                TokenType.LITERAL -> {
                    if (!text.startsWith(token.literal, position)) return null
                    return walk(tokenIndex + 1, position + token.literal.length, captures)
                }

                TokenType.GROUP -> {
                    for (alternative in token.alternatives) {
                        if (!text.startsWith(alternative, position)) continue
                        val hit = walk(tokenIndex + 1, position + alternative.length, captures)
                        if (hit != null) return hit
                    }
                    return if (token.optional) walk(tokenIndex + 1, position, captures) else null
                }

                TokenType.CAPTURE -> {
                    for (candidate in captureMatches(token, text, position)) {
                        val hit = walk(
                            tokenIndex + 1,
                            position + candidate.length,
                            captures + candidate
                        )
                        if (hit != null) return hit
                    }
                    return null
                }
            }
        }

        val hit = walk(0, start, emptyList()) ?: return null
        if (hit.end <= start) return null

        val first = rule.tokens.first()
        val last = rule.tokens.last()
        val continuesNumberBefore = start > 0 && (
            isNumericChar(text[start - 1]) ||
                (start > 1 && isNumericGroupSeparator(text[start - 1]) && isNumericChar(text[start - 2]))
            )
        val continuesNumberAfter = hit.end < text.length && (
            isNumericChar(text[hit.end]) ||
                (hit.end + 1 < text.length && isNumericGroupSeparator(text[hit.end]) && isNumericChar(text[hit.end + 1]))
            )

        if (first.type == TokenType.CAPTURE && first.sources.any { it == "n" || it == "y" } && continuesNumberBefore) {
            return null
        }
        if (last.type == TokenType.CAPTURE && last.sources.any { it == "n" || it == "y" } && continuesNumberAfter) {
            return null
        }

        var translation = rule.translation
        hit.captures.forEachIndexed { index, capture ->
            translation = translation.replace("{${index}}", capture.value)
        }

        return Match(
            start = start,
            end = hit.end,
            source = text.substring(start, hit.end),
            translation = translation,
            pattern = rule.pattern,
            line = rule.line
        )
    }

    private fun captureMatches(token: Token, text: String, position: Int): List<Capture> {
        val available = minOf(token.max, text.length - position)
        val matches = ArrayList<Capture>()

        for (source in token.sources) {
            for (length in available downTo token.min) {
                if (length <= 0) continue
                val raw = text.substring(position, position + length)
                val value = lookupSource(source, raw) ?: continue
                matches.add(Capture(length, raw, value))
            }
            if (matches.isNotEmpty()) break
        }

        return matches
    }

    private fun lookupSource(source: String, raw: String): String? {
        when (source) {
            "n" -> {
                if (!isNumericText(raw)) return null
                return chineseToLatin(raw)
            }

            "y" -> {
                if (!isNumericText(raw)) return null
                return yearToLatin(raw)
            }

            "L" -> return if (raw.length == 1) LABELS[raw[0]] else null

            "hv" -> {
                if (raw.length != 1) return null
                return dictionaries.pa[raw]?.let { firstMeaning(it, separator) }
            }
        }

        val dictionaryOrder = when (source) {
            "ne" -> listOf(dictionaries.names)
            "pn" -> listOf(dictionaries.pronouns)
            "vp" -> listOf(dictionaries.vp)
            else -> listOf(dictionaries.names, dictionaries.pronouns, dictionaries.vp)
        }

        for (dictionary in dictionaryOrder) {
            dictionary[raw]?.let { return firstMeaning(it, separator) }
        }
        return null
    }

    private fun longestVpAt(text: String, start: Int): Int {
        val max = minOf(text.length - start, vpMaxLen)
        for (length in max downTo 1) {
            if (dictionaries.vp.containsKey(text.substring(start, start + length))) return length
        }
        return 0
    }

    companion object {
        private const val DEFAULT_SEPARATOR = "/"
        private const val MAX_RANGE = 64

        private val NUM_DIGITS = mapOf(
            '零' to 0, '〇' to 0, '○' to 0,
            '一' to 1, '二' to 2, '两' to 2, '兩' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
        )
        private val SMALL_UNITS = mapOf('十' to 10, '百' to 100, '千' to 1000)
        private val LARGE_UNITS = mapOf('万' to 10_000L, '萬' to 10_000L, '亿' to 100_000_000L, '億' to 100_000_000L)
        private val NUMERIC_UNIT_CHARS = SMALL_UNITS.keys + LARGE_UNITS.keys
        private val NUMERIC_CHARS = NUM_DIGITS.keys + ('0'..'9') + NUMERIC_UNIT_CHARS
        private val NUMERIC_GROUP_SEPARATORS = setOf(',', '，')
        private val VALID_SOURCES = setOf("n", "y", "L", "ne", "pn", "vp", "hv", "w")
        private val COMMON_ANCHORS = setOf('的', '了', '是', '不', '在')
        private val LABELS = mapOf(
            '章' to "Chương", '卷' to "Quyển", '集' to "Tập", '节' to "Tiết",
            '節' to "Tiết", '幕' to "Màn", '回' to "Hồi", '折' to "Chiết"
        )
        private val PLACEHOLDER_RE = Regex("\\{(\\d+)\\}")
        private val ESCAPED_GROUP_CHAR_RE = Regex("\\\\([|()])")
        private val RANGE_RE = Regex("^(\\d+)(?:-(\\d+))?$")

        fun create(
            ruleText: String,
            dictionaries: VietPhraseDictionaries,
            separator: String = DEFAULT_SEPARATOR,
            vpMaxLen: Int = 64
        ): VietPhraseRuleEngine {
            val parsed = parseRules(ruleText)
            return VietPhraseRuleEngine(
                dictionaries = dictionaries,
                separator = separator.ifEmpty { DEFAULT_SEPARATOR },
                vpMaxLen = vpMaxLen.coerceAtLeast(1),
                rules = parsed.rules,
                errors = parsed.errors
            )
        }

        private fun parseRules(text: String): ParsedRules {
            val rules = ArrayList<Rule>()
            val errors = ArrayList<ParseError>()
            val seen = HashSet<String>()

            text.removePrefix("\uFEFF").lineSequence().forEachIndexed { index, rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEachIndexed

                val separatorIndex = line.indexOf('=')
                if (separatorIndex < 1) {
                    errors.add(ParseError(index + 1, "missing =", rawLine))
                    return@forEachIndexed
                }

                val pattern = line.substring(0, separatorIndex).trim()
                val translation = line.substring(separatorIndex + 1).trim()
                if (translation.isEmpty()) {
                    errors.add(ParseError(index + 1, "empty translation", rawLine))
                    return@forEachIndexed
                }
                if (!seen.add(pattern)) {
                    errors.add(ParseError(index + 1, "duplicate", rawLine))
                    return@forEachIndexed
                }

                try {
                    rules.add(compileRule(pattern, translation, index + 1))
                } catch (error: Exception) {
                    errors.add(ParseError(index + 1, error.message ?: "invalid rule", rawLine))
                }
            }

            rules.sortWith(
                compareByDescending<Rule> { it.specificity }
                    .thenBy { it.wildcardCount }
                    .thenBy { it.pattern }
            )
            return ParsedRules(
                rules.mapIndexed { order, rule -> rule.copy(order = order) },
                errors
            )
        }

        private fun compileRule(pattern: String, translation: String, line: Int): Rule {
            val tokens = parsePattern(pattern)
            val captures = tokens.filter { it.type == TokenType.CAPTURE }
            val anchor = getAnchor(tokens) ?: throw IllegalArgumentException("rule has no anchor")
            if (captures.isEmpty()) throw IllegalArgumentException("rule has no wildcard")

            val used = HashSet<Int>()
            PLACEHOLDER_RE.findAll(translation).forEach { match ->
                match.groupValues[1].toIntOrNull()?.let { used.add(it) }
            }
            if (used.any { it >= captures.size }) {
                throw IllegalArgumentException("placeholder does not exist")
            }
            if (captures.indices.any { it !in used }) {
                throw IllegalArgumentException("capture is not used")
            }

            val specificity = tokens.sumOf { token ->
                when (token.type) {
                    TokenType.LITERAL -> token.literal.length
                    TokenType.GROUP -> token.alternatives.maxOfOrNull { it.length } ?: 0
                    TokenType.CAPTURE -> token.max
                }
            }

            return Rule(
                pattern = pattern,
                translation = translation,
                line = line,
                tokens = tokens,
                anchor = anchor,
                specificity = specificity,
                wildcardCount = captures.size,
                order = 0
            )
        }

        private fun parsePattern(pattern: String): List<Token> {
            val tokens = ArrayList<Token>()
            val literal = StringBuilder()

            fun flushLiteral() {
                if (literal.isNotEmpty()) {
                    tokens.add(Token(TokenType.LITERAL, literal = literal.toString()))
                    literal.clear()
                }
            }

            var index = 0
            while (index < pattern.length) {
                when (pattern[index]) {
                    '<' -> {
                        flushLiteral()
                        val end = pattern.indexOf('>', index + 1)
                        if (end < 0) throw IllegalArgumentException("missing >")
                        tokens.add(parseCapture(pattern.substring(index + 1, end)))
                        index = end + 1
                    }

                    '(' -> {
                        flushLiteral()
                        val group = parseGroup(pattern, index)
                        tokens.add(group.first)
                        index = group.second
                    }

                    else -> {
                        literal.append(pattern[index])
                        index++
                    }
                }
            }
            flushLiteral()
            return tokens
        }

        private fun parseGroup(pattern: String, start: Int): Pair<Token, Int> {
            var end = start + 1
            var escaped = false
            while (end < pattern.length) {
                val ch = pattern[end]
                if (escaped) {
                    escaped = false
                    end++
                    continue
                }
                if (ch == '\\') {
                    escaped = true
                    end++
                    continue
                }
                if (ch == ')') break
                end++
            }
            if (end >= pattern.length) throw IllegalArgumentException("missing )")

            val body = pattern.substring(start + 1, end)
            val alternatives = body.split('|').map { it.replace(ESCAPED_GROUP_CHAR_RE, "$1") }
            if (alternatives.any { it.isEmpty() }) throw IllegalArgumentException("empty group")

            val optional = end + 1 < pattern.length && pattern[end + 1] == '?'
            val token = Token(
                type = TokenType.GROUP,
                alternatives = alternatives,
                optional = optional
            )
            return token to (end + if (optional) 2 else 1)
        }

        private fun parseCapture(raw: String): Token {
            val parts = raw.split(':')
            if (parts.size > 2) throw IllegalArgumentException("invalid token")

            val sources = parts[0].split('|')
            if (sources.isEmpty() || sources.any { it !in VALID_SOURCES }) {
                throw IllegalArgumentException("unknown token: <$raw>")
            }

            val fixed = sources.size == 1 && (sources[0] == "L" || sources[0] == "hv")
            val (min, max) = parseRange(parts.getOrNull(1), if (fixed) 1 else 12)
            if (fixed && (min != 1 || max != 1)) {
                throw IllegalArgumentException("token <${sources[0]}> only accepts one character")
            }

            return Token(
                type = TokenType.CAPTURE,
                sources = sources,
                min = min,
                max = max
            )
        }

        private fun parseRange(raw: String?, defaultMax: Int): Pair<Int, Int> {
            if (raw.isNullOrBlank()) return 1 to defaultMax
            val match = RANGE_RE.matchEntire(raw) ?: throw IllegalArgumentException("invalid range")
            val min = match.groupValues[1].toIntOrNull() ?: throw IllegalArgumentException("invalid range")
            val max = (match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()) ?: min
            if (min < 1 || max < min || max > MAX_RANGE) {
                throw IllegalArgumentException("invalid range")
            }
            return min to max
        }

        private fun getAnchor(tokens: List<Token>): String? {
            data class Candidate(val value: String)

            val candidates = ArrayList<Candidate>()
            for (token in tokens) {
                if (token.type == TokenType.LITERAL && token.literal.isNotEmpty()) {
                    candidates.add(Candidate(token.literal))
                }
                if (token.type == TokenType.GROUP && !token.optional) {
                    for (value in token.alternatives) candidates.add(Candidate(value))
                }
            }

            candidates.sortWith(
                compareBy<Candidate> {
                    if (it.value.length == 1 && COMMON_ANCHORS.contains(it.value[0])) 1 else 0
                }.thenByDescending { it.value.length }
            )
            return candidates.firstOrNull()?.value
        }

        private fun firstMeaning(value: String, separator: String): String {
            var end = value.length
            for (candidate in listOf(separator, "¦")) {
                if (candidate.isEmpty()) continue
                val at = value.indexOf(candidate)
                if (at in 0 until end) end = at
            }
            return value.substring(0, end).trim()
        }

        private fun isNumericText(raw: String): Boolean {
            return raw.isNotEmpty() && raw.all { it in NUMERIC_CHARS }
        }

        private fun isNumericChar(ch: Char): Boolean = ch in NUMERIC_CHARS
        private fun isNumericGroupSeparator(ch: Char): Boolean = ch in NUMERIC_GROUP_SEPARATORS

        private fun digitOf(ch: Char): Int? {
            if (ch in '0'..'9') return ch - '0'
            return NUM_DIGITS[ch]
        }

        private fun yearToLatin(raw: String): String? {
            val output = StringBuilder(raw.length)
            for (ch in raw) {
                output.append(digitOf(ch) ?: return null)
            }
            return output.toString()
        }

        private fun chineseToLatin(raw: String): String? {
            if (raw.all { it in '0'..'9' }) {
                return runCatching { BigInteger(raw).toString() }.getOrNull()
            }
            if (raw.none { it in NUMERIC_UNIT_CHARS }) return yearToLatin(raw)

            var total = 0L
            var section = 0L
            var number = 0L
            var saw = false

            for (ch in raw) {
                val digit = digitOf(ch)
                if (digit != null) {
                    number = digit.toLong()
                    saw = true
                    continue
                }

                val small = SMALL_UNITS[ch]
                if (small != null) {
                    section += (if (number == 0L) 1 else number) * small
                    number = 0
                    saw = true
                    continue
                }

                val large = LARGE_UNITS[ch]
                if (large != null) {
                    section += number
                    total += (if (section == 0L) 1 else section) * large
                    section = 0
                    number = 0
                    saw = true
                    continue
                }

                return null
            }

            return if (saw) (total + section + number).toString() else null
        }
    }
}
