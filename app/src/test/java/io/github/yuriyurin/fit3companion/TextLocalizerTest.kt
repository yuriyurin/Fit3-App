package io.github.yuriyurin.fit3companion

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class TextLocalizerTest {
    private fun file(name: String): File = File("src/main/res/$name").takeIf { it.exists() }
        ?: File("app/src/main/res/$name")

    private fun androidText(value: String): String {
        val quoted = value.removeSurrounding("\"")
        return Regex("\\\\(.)").replace(quoted) { when (it.groupValues[1]) {
            "n" -> "\n"; "t" -> "\t"; else -> it.groupValues[1]
        } }
    }

    private fun strings(language: String): Map<String, String> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file("$language/strings.xml"))
        val list = root.getElementsByTagName("string")
        return (0 until list.length).associate { index ->
            val item = list.item(index) as Element
            item.getAttribute("name") to androidText(item.textContent)
        }
    }

    private val russian = strings("values-ru")
    private val english = strings("values")
    private val pairs = russian.filterKeys { it.removePrefix("loc_").toInt() !in 520..527 }
        .map { (key, value) -> value to english.getValue(key) }
    private val localizer = TextLocalizer(pairs)

    @Test fun resourceSetsAreCompleteAndArgumentsMatch() {
        assertEquals(625, russian.size)
        listOf(404, 430, 431, 432, 433, 434, 458, 459).forEach {
            assertFalse("Retired debug label $it", russian.containsKey("loc_$it"))
        }
        assertEquals(russian.keys, english.keys)
        val placeholder = Regex("\\{\\d+}")
        russian.forEach { (key, value) ->
            assertEquals(key, placeholder.findAll(value).map { it.value }.sorted().toList(),
                placeholder.findAll(english.getValue(key)).map { it.value }.sorted().toList())
            assertFalse(key, english.getValue(key).any { it in '\u0400'..'\u04ff' })
        }
    }

    @Test fun everyLegacyLabelAndTemplateHasAnEnglishPresentation() {
        val variable = Regex("\\{(\\d+)}")
        pairs.forEach { (ru, en) ->
            val source = variable.replace(ru) { "x${it.groupValues[1]}" }
            val expected = variable.replace(en) { "x${it.groupValues[1]}" }
            assertEquals(ru, expected, localizer.translate(source))
        }
    }

    @Test fun dynamicNumbersAndUnitsArePreserved() {
        assertEquals("Data updated: 20:10", localizer.translate("Данные обновлены: 20:10"))
        assertEquals("Goal 10000 · 50%", localizer.translate("Цель 10000 · 50%"))
        assertEquals("5h 14m", localizer.translate("5ч 14м"))
        assertEquals("87.5 m", localizer.translate("87.5 м"))
        assertEquals("233 steps", localizer.translate("233 шагов"))
        assertEquals("%.2f km", localizer.translate("%.2f км"))
        assertEquals("Every 7 days", localizer.translate("Раз в 7 дней"))
    }

    @Test fun nestedStatusAndOptionalSuffixTranslate() {
        assertEquals("Data channel: ready · packets in memory: 42",
            localizer.translate("Канал данных: готов · пакетов в памяти: 42"))
        assertEquals("Could not restore: Invalid backup format",
            localizer.translate("Не удалось восстановить: Неверный формат копии"))
        assertEquals("Steps: 1234 (partial total)",
            localizer.translate("Шаги: 1234 (неполная сумма)"))
        assertEquals("Battery 39% · charging", localizer.translate("Заряд 39% · зарядка"))
    }

    @Test fun externalContentAndWireDataStayUnchanged() {
        listOf("Москва", "Nekogram", "Dynamic digital", "Тестовый контакт: привет!", "87 E0 11 27",
            "com.spotify.music", "SM-R390_10022_256x402.bin", "1234").forEach {
            assertEquals(it, localizer.translate(it))
        }
    }

    @Test fun multilineDiagnosticPresentationKeepsNumbers() {
        assertEquals("Fit3 App · log summary\nConnection: Connected\nSteps: 1234",
            localizer.translate("Fit3 App · краткий журнал\nСоединение: Подключено\nШаги: 1234"))
    }
}
