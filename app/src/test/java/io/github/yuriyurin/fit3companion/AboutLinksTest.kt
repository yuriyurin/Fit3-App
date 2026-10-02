package io.github.yuriyurin.fit3companion

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class AboutLinksTest {
    @Test fun linksPointToRequestedAuthorAndSupportPage() {
        assertEquals("https://4pda.to/forum/index.php?showuser=4729981", AboutLinks.AUTHOR)
        assertEquals("https://www.donationalerts.com/r/yuriyurinnn", AboutLinks.SUPPORT)
    }

    @Test fun aboutActionsHaveRussianAndEnglishLabels() {
        fun labels(language: String): Map<String, String> {
            val file = File("src/main/res/$language/about.xml").takeIf { it.exists() }
                ?: File("app/src/main/res/$language/about.xml")
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(file).getElementsByTagName("string")
            return (0 until nodes.length).associate {
                val item = nodes.item(it) as Element
                item.getAttribute("name") to item.textContent
            }
        }
        val russian = labels("values-ru")
        val english = labels("values")
        assertEquals(russian.keys, english.keys)
        assertEquals("Поддержать", russian.getValue("about_support"))
        assertEquals("Support", english.getValue("about_support"))
    }
}
