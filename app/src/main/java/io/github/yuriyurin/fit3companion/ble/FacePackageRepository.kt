package io.github.yuriyurin.fit3companion.ble

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Fetches a stock Fit3 package and extracts only its watch-face BIN; never feeds an APK to OTA. */
internal class FacePackageRepository(private val context: Context) {
    data class Payload(val faceId: Int, val samplerId: Int, val fileName: String, val binary: ByteArray,
        val customKey: String? = null, val repairedVariantCount: Boolean = false)

    fun download(face: OfficialFace, style: OfficialFaceStyle): Payload {
        require(face.id in 0..255 && style.id in 0..255 && face.appId.matches(APP_ID))
        require(face.versionCode > 0 && face.styles.any { it.id == style.id })
        val check = parseXml(request(stubUrl("stub/gearAppUpdateCheck.as", "${face.appId}@${face.versionCode}")))
        require(check.directText("resultCode") == "0") { "Магазин не подтвердил проверку версии" }
        val updateCode = check.directChild("appInfo")?.directText("resultCode")?.toIntOrNull()
        require(updateCode == 1 || updateCode == 2) { "Магазин не предлагает этот циферблат" }
        val root = parseXml(request(stubUrl("stub/gearAppDownload.as", face.appId)))
        // Unlike the update-check endpoint, Samsung's successful download response has
        // no root resultCode: the status belongs to appInfo (verified against a live reply).
        val app = root.directChild("appInfo") ?: error("Магазин вернул неполный ответ")
        require(app.directText("resultCode") == "1") { "Магазин отказал в загрузке" }
        val url = app.directText("downloadURI")
        val declaredSize = app.directText("contentSize").toLongOrNull() ?: -1L
        require(declaredSize in 1..MAX_APK_BYTES) { "Недопустимый размер пакета" }
        val apk = request(url, MAX_APK_BYTES)
        require(apk.size.toLong() == declaredSize) { "Пакет загружен не полностью" }
        return extract(face.id, style.id, apk)
    }

    private fun stubUrl(path: String, appInfo: String): String {
        val values = linkedMapOf(
            "csc" to "NONE", "sdkVer" to Build.VERSION.SDK_INT.toString(),
            "callerId" to "com.samsung.wearable.fit3plugin", "versionCode" to "126071051",
            "mcc" to "450", "mnc" to "10",
            "systemId" to (System.currentTimeMillis() - SystemClock.elapsedRealtime()).toString(),
            "extuk" to (Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""),
            "abiType" to if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "64" else "32",
            "deviceId" to "SM-R390", "loginType" to "N", "oneUiVersion" to "0",
            "cc" to "KOR", "pd" to "0", "appInfo" to appInfo,
            "hashValue" to Base64.encodeToString(MessageDigest.getInstance("SHA-1")
                .digest((appInfo + "GALAXYAPPSAPI").toByteArray(Charsets.ISO_8859_1)), Base64.NO_WRAP),
        )
        return "https://vas.samsungapps.com/$path?" + values.entries.joinToString("&") {
            "${enc(it.key)}=${enc(it.value)}"
        }
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun request(location: String, limit: Int = 2_000_000): ByteArray {
        var url = URL(location)
        repeat(4) {
            require(url.protocol == "https" && trustedHost(url.host)) { "Недоверенный адрес загрузки" }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 12_000
                readTimeout = 20_000
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    url = URL(url, connection.getHeaderField("Location") ?: error("Редирект без адреса"))
                    return@repeat
                }
                require(code == 200) { "Магазин ответил HTTP $code" }
                require(connection.contentLengthLong <= limit) { "Пакет слишком большой" }
                val out = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val chunk = ByteArray(8192)
                    while (true) {
                        val count = input.read(chunk)
                        if (count < 0) break
                        require(out.size() + count <= limit) { "Пакет слишком большой" }
                        out.write(chunk, 0, count)
                    }
                }
                return out.toByteArray()
            } finally { connection.disconnect() }
        }
        error("Слишком много перенаправлений магазина")
    }

    private fun trustedHost(host: String): Boolean = host == "samsungapps.com" ||
        host.endsWith(".samsungapps.com") || host == "galaxyappstore.com" ||
        host.endsWith(".galaxyappstore.com")

    private fun parseXml(bytes: ByteArray): Element {
        val xml = bytes.toString(Charsets.UTF_8)
        require(!xml.contains("<!DOCTYPE", ignoreCase = true) &&
            !xml.contains("<!ENTITY", ignoreCase = true)) { "Магазин вернул недопустимый XML" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isExpandEntityReferences = false
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
    }

    private fun Element.directChild(name: String): Element? = (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }.firstOrNull { it.tagName == name }
    private fun Element.directText(name: String): String = directChild(name)?.textContent?.trim().orEmpty()

    internal fun extract(faceId: Int, styleId: Int, apk: ByteArray): Payload {
        require(apk.size <= MAX_APK_BYTES)
        val expectedName = "SM-R390_${faceId.toString().padStart(5, '0')}_256x402.bin"
        var binary: ByteArray? = null
        var inflated = 0L
        var entries = 0
        ZipInputStream(ByteArrayInputStream(apk)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= 4096) { "В пакете слишком много файлов" }
                val out = if (!entry.isDirectory && entry.name.substringAfterLast('/') == expectedName)
                    ByteArrayOutputStream() else null
                val chunk = ByteArray(8192)
                while (true) {
                    val count = zip.read(chunk)
                    if (count < 0) break
                    inflated += count
                    require(inflated <= 64L * 1024 * 1024) { "Распакованный пакет слишком большой" }
                    if (out != null) {
                        require(out.size() + count <= FaceBinValidator.MAX_BIN_BYTES) { "Циферблат больше безопасного лимита 4 МиБ" }
                        out.write(chunk, 0, count)
                    }
                }
                if (out != null) {
                    require(binary == null) { "В пакете несколько файлов циферблата" }
                    binary = out.toByteArray()
                }
                zip.closeEntry()
            }
        }
        val bytes = binary ?: error("Этот циферблат не содержит обычного BIN (например, Photos)")
        val report = FaceBinValidator.validate(bytes, sourceName = expectedName, expectedFaceId = faceId)
        require(styleId in report.styles) { "Выбранный стиль отсутствует внутри BIN" }
        return Payload(faceId, styleId, report.canonicalFileName, bytes)
    }


    companion object {
        private const val MAX_APK_BYTES = 32 * 1024 * 1024
        private val APP_ID = Regex("com\\.samsung\\.fit3watchface\\.sm_r390_\\d{4,5}", RegexOption.IGNORE_CASE)
    }
}
