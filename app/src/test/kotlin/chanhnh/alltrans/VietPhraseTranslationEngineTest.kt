package chanhnh.alltrans

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VietPhraseTranslationEngineTest {
    @Test
    fun chapterRuleUsesNumber() {
        val engine = createEngine(ruleText = "第<n>章=Chương {0}")
        assertEquals("Chương 3", engine.translate("第三章", advanced = true))
    }

    @Test
    fun labelTokenUsesLabelDictionary() {
        val engine = createEngine(ruleText = "第<L>卷=Quyển {0}")
        assertEquals("Quyển Chương", engine.translate("第章卷", advanced = true))
    }

    @Test
    fun numberRuleConvertsChineseNumber() {
        val engine = createEngine(ruleText = "<n>个={0} cái")
        assertEquals("123 cái", engine.translate("一百二十三个", advanced = true))
    }

    @Test
    fun yearRuleConvertsChineseYear() {
        val engine = createEngine(ruleText = "<y>年=năm {0}")
        assertEquals("Năm 2024", engine.translate("二〇二四年", advanced = true))
    }

    @Test
    fun pronounRuleUsesPronounDictionary() {
        val engine = createEngine(
            ruleText = "<pn>们={0} (plural)",
            pronouns = mapOf("朋友" to "bạn"),
            vp = mapOf("朋友" to "bạn bè")
        )
        assertEquals("Bạn (plural)", engine.translate("朋友们", advanced = true))
    }

    @Test
    fun namesAreKeptAndTranslatedBeforeDictionaries() {
        val engine = createEngine(
            names = mapOf("王小明" to "Vương Tiểu Minh"),
            vp = mapOf("来了" to "đã đến")
        )
        assertEquals("Vương Tiểu Minh đã đến", engine.translate("王小明来了", advanced = true))
    }

    @Test
    fun punctuationAndSentenceCapitalizationAreApplied() {
        val engine = createEngine(
            vp = mapOf(
                "你好" to "xin chào",
                "世界" to "thế giới"
            )
        )
        assertEquals("Xin chào, thế giới.", engine.translate("你好，世界。", advanced = true))
    }

    @Test
    fun longerRuleWinsOverDictionaryButEqualRuleDoesNot() {
        val longerRule = createEngine(
            ruleText = "<w>世界={0} đặc biệt",
            vp = mapOf("你好" to "xin chào", "世界" to "thế giới")
        )
        assertEquals("Xin chào đặc biệt", longerRule.translate("你好世界", advanced = true))

        val equalRule = createEngine(
            ruleText = "你<w>=Chào {0}",
            vp = mapOf("你好" to "xin chào", "好" to "tốt")
        )
        assertEquals("Xin chào", equalRule.translate("你好", advanced = true))
    }

    @Test
    fun emptyFirstMeaningRemovesTheTokenInsteadOfLeavingChineseText() {
        val engine = createEngine(
            vp = mapOf(
                "最" to "nhất",
                "的" to "/của/đích"
            )
        )
        assertEquals("Nhất", engine.translate("最的", advanced = true))
    }

    @Test
    fun newlineBetweenClausesFollowsTheUserscriptSpacingRules() {
        val engine = createEngine(
            vp = mapOf(
                "你好" to "ngươi tốt",
                "世界" to "thế giới"
            )
        )
        assertEquals("Ngươi tốt, thế giới", engine.translate("你好，\n世界", advanced = true))
    }

    @Test
    fun legacyModeStillUsesTheOldDictionaryPath() {
        val engine = createEngine(
            vp = mapOf(
                "你好" to "xin chào/tạm biệt",
                "世界" to "thế giới"
            )
        )
        assertEquals("Xin chào, thế giới.", engine.translate("你好，世界。", advanced = false))
    }

    @Test
    fun invalidRulesAreIgnored() {
        val engine = createEngine(
            ruleText = "这个规则没有等号\n<bad>规则=bad\n<n>个={0} cái",
            vp = emptyMap()
        )
        assertTrue(engine.rules.errors.any { it.line == 1 })
        assertTrue(engine.rules.errors.any { it.line == 2 })
        assertEquals("123 cái", engine.translate("一百二十三个", advanced = true))
    }

    private fun createEngine(
        ruleText: String = "",
        pa: Map<String, String> = emptyMap(),
        vp: Map<String, String> = emptyMap(),
        names: Map<String, String> = emptyMap(),
        pronouns: Map<String, String> = emptyMap()
    ): VietPhraseTranslationEngine {
        val dictionaries = VietPhraseDictionaries(
            pa = pa,
            vp = vp,
            names = names,
            pronouns = pronouns
        )
        val rules = VietPhraseRuleEngine.create(
            ruleText = ruleText,
            dictionaries = dictionaries,
            separator = "/",
            vpMaxLen = vp.keys.maxOfOrNull { it.length } ?: 1
        )
        return VietPhraseTranslationEngine(dictionaries, rules, "/")
    }
}
