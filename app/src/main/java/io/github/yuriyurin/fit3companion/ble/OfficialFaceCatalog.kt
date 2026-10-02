package io.github.yuriyurin.fit3companion.ble

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class OfficialFaceStyle(val id: Int, val previewUrl: String)
data class OfficialFace(
    val id: Int,
    val name: String,
    val styles: List<OfficialFaceStyle>,
    val appId: String = "",
    val versionCode: Long = 0,
    val productId: String = "",
)

/** Store metadata and previews are fetched on demand; Samsung artwork is not bundled. */
class OfficialFaceCatalog(private val context: Context) {
    private val catalogFile = File(context.filesDir, "official-faces.json")
    private val previewDir = File(context.cacheDir, "official-face-previews")

    fun load(): List<OfficialFace> {
        val cached = runCatching { decode(catalogFile.readText()) }.getOrNull()
        if (!cached.isNullOrEmpty() && cached.all { it.appId.isNotBlank() && it.versionCode > 0 } &&
            System.currentTimeMillis() - catalogFile.lastModified() < 7 * 86_400_000L)
            return cached
        return try {
            val result = fetch()
            if (result.isNotEmpty()) {
                catalogFile.writeText(encode(result))
                result
            } else cached ?: emptyList()
        } catch (error: Exception) {
            cached ?: throw error
        }
    }

    fun preview(url: String): Bitmap? {
        if (!allowedPreview(url)) return null
        previewDir.mkdirs()
        val name = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(previewDir, "$name.png")
        if (file.exists()) BitmapFactory.decodeFile(file.path)?.let { return it }
        val bytes = readUrl(url, 700_000)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        file.writeBytes(bytes)
        return bitmap
    }

    private fun fetch(): List<OfficialFace> {
        val faces = linkedMapOf<Int, OfficialFace>()
        for (page in 0..4) {
            val start = page * 100 + 1
            val endpoint = "https://vas.samsungapps.com/product/getContentCategoryProductList.as" +
                "?imgWidth=216&imgHeight=432&startNum=$start&endNum=${start + 99}&status=1" +
                "&cc=KOR&extraInfo=screenshot&callerId=com.samsung.wearable.fit3plugin" +
                "&locale=en_US&alignOrder=recent&contentCategoryID=0000004252" +
                "&mcc=450&mnc=10&csc=NONE&deviceId=SM-R390&sdkVer=35&pd=0"
            val factory = DocumentBuilderFactory.newInstance().apply {
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
                isExpandEntityReferences = false
            }
            val xml = readUrl(endpoint, 5_000_000)
            require(!xml.toString(Charsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true) &&
                !xml.toString(Charsets.UTF_8).contains("<!ENTITY", ignoreCase = true)) {
                "Магазин вернул недопустимый XML"
            }
            val document = factory.newDocumentBuilder().parse(xml.inputStream())
            val root = document.documentElement
            val resultCode = root.childText("resultCode").toIntOrNull()
            if (resultCode == 1007 && page > 0) break
            if (resultCode != 0) error("Каталог недоступен (код ${resultCode ?: "?"})")
            val entries = root.getElementsByTagName("appInfo")
            for (i in 0 until entries.length) {
                val entry = entries.item(i) as? Element ?: continue
                val id = Regex("sm_r390_(\\d{4,5})$", RegexOption.IGNORE_CASE)
                    .find(entry.childText("appId"))?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val base = entry.childText("screenShotImgURL").removeSuffix(".png")
                val resolutions = entry.childText("screenShotResolution").split('|')
                val indices = entry.childText("screenShotIndex").split('|')
                val styles = resolutions.withIndex().filter { it.value.equals("256x402", true) }
                    .mapIndexedNotNull { styleId, shot ->
                        val index = indices.getOrNull(shot.index)?.trim()?.toIntOrNull() ?: shot.index + 1
                        val url = "${base}_256_402_$index.png"
                        if (allowedPreview(url)) OfficialFaceStyle(styleId, url) else null
                    }
                if (styles.isNotEmpty()) faces[id] = OfficialFace(id,
                    entry.childText("productName").ifBlank { knownFaceName(id) ?: "Циферблат" },
                    styles, entry.childText("appId"),
                    entry.childText("versionCode").toLongOrNull() ?: 0,
                    entry.childText("productID"))
            }
            if (entries.length < 100) break
        }
        return faces.values.toList()
    }

    private fun readUrl(url: String, maxBytes: Int): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 12_000
        try {
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (output.size() + n > maxBytes) error("Слишком большой ответ")
                    output.write(buffer, 0, n)
                }
                return output.toByteArray()
            }
        } finally { connection.disconnect() }
    }

    private fun allowedPreview(url: String): Boolean {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return false
        return parsed.protocol == "https" && (parsed.host == "img.samsungapps.com" ||
            parsed.host.endsWith(".samsungapps.com"))
    }

    private fun encode(faces: List<OfficialFace>) = JSONArray().apply {
        faces.forEach { face -> put(JSONObject().put("id", face.id).put("name", face.name)
            .put("appId", face.appId).put("versionCode", face.versionCode)
            .put("productId", face.productId)
            .put("styles", JSONArray().apply { face.styles.forEach { style ->
                put(JSONObject().put("id", style.id).put("url", style.previewUrl))
            } })) }
    }.toString()

    private fun decode(json: String): List<OfficialFace> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val styles = item.optJSONArray("styles") ?: return@mapNotNull null
            OfficialFace(item.optInt("id"), item.optString("name"),
                (0 until styles.length()).mapNotNull { j ->
                    styles.optJSONObject(j)?.let { OfficialFaceStyle(it.optInt("id"), it.optString("url")) }
                }, item.optString("appId"), item.optLong("versionCode"),
                item.optString("productId"))
        }
    }
}

fun knownFaceName(id: Int): String? = when (id) {
    22 -> "Dynamic digital"
    28 -> "Info brick"
    46 -> "Minimalist"
    79 -> "Step streak"
    106 -> "Fitness pro 3"
    108 -> "Step count"
    254 -> "Photos"
    else -> null
}

private fun Element.childText(name: String): String {
    val children = childNodes
    for (i in 0 until children.length) {
        val child = children.item(i)
        if (child.nodeName == name) return child.textContent?.trim().orEmpty()
    }
    return ""
}
