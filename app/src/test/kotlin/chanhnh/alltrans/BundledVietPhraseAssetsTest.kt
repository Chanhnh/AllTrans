package chanhnh.alltrans

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class BundledVietPhraseAssetsTest {
    @Test
    fun bundledRuleFileParses() {
        val file = listOf(
            File("src/main/assets/vietphrase/rule.txt"),
            File("app/src/main/assets/vietphrase/rule.txt")
        ).firstOrNull { it.isFile }
        assumeTrue("Bundled rule.txt is only available in the project tree", file != null)

        val engine = VietPhraseRuleEngine.create(
            ruleText = file!!.readText(),
            dictionaries = VietPhraseDictionaries(
                pa = emptyMap(),
                vp = emptyMap(),
                names = emptyMap(),
                pronouns = emptyMap()
            )
        )

        println("Bundled rules: ${engine.rules.size}, errors: ${engine.errors.size}")
        assertEquals("Bundled rule count changed; update this test if the asset is updated", 774, engine.rules.size)
        assertEquals("All bundled rules must parse", 0, engine.errors.size)

        val year = engine.matchAt("二〇二四年", 0)
        assertTrue(year != null)
        assertTrue(year!!.translation == "năm 2024")
        assertTrue(year.end == 5)

        val date = engine.matchAt("2024年3月5日", 0)
        assertTrue(date != null)
        assertTrue(date!!.translation == "ngày 5 tháng 3 năm 2024")
        assertTrue(date.end == 9)
    }
}
