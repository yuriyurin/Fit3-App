package io.github.yuriyurin.fit3companion.ble

import android.content.Context
import android.graphics.Bitmap
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

data class CustomFace(val key: String, val name: String, val faceId: Int,
    val styles: List<Int>, val bytes: Int, val addedAt: Long)

/** Private durable library. Import NEVER contacts the watch. Display names are not file paths. */
class CustomFaceLibrary(private val context: Context) {
    private val root = File(context.filesDir, "custom-watchfaces").apply { check(mkdirs() || isDirectory) }
    private val index = AtomicFile(File(root, "library.json"))
    @Synchronized fun list(): List<CustomFace> {
        if (!index.baseFile.exists() && !File(root, "library.json.bak").exists()) return emptyList()
        val array = JSONArray(index.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        require(array.length() <= 200)
        return (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            val key = item.getString("key"); require(KEY.matches(key))
            val styles = item.getJSONArray("styles")
            CustomFace(key, item.getString("name"), item.getInt("faceId"),
                (0 until styles.length()).map { styles.getInt(it) }, item.getInt("bytes"), item.getLong("addedAt"))
        }.filter { file(it.key).isFile }.sortedByDescending { it.addedAt }
    }
    @Synchronized fun add(bytes: ByteArray, sourceName: String): CustomFace {
        val report = FaceBinValidator.validateLocal(bytes, sourceName)
        val previous = list()
        val key = digest(bytes)
        previous.firstOrNull { it.key == key }?.let { return it }
        require(previous.size < 200 && previous.sumOf { it.bytes.toLong() } + bytes.size <= 128L * 1024 * 1024) {
            "Custom watchface library is full"
        }
        val displayName = sourceName.substringAfterLast('/').substringAfterLast('\\')
            .removeSuffix(".bin").take(60).trim().ifBlank { "Custom watchface" }
        val face = CustomFace(key, displayName, report.faceId, report.styles, bytes.size, System.currentTimeMillis())
        val temp = File.createTempFile("import-", ".tmp", root)
        try {
            FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            check(temp.renameTo(file(key))) { "Could not save watchface" }
            try { persist(previous + face) } catch (error: Exception) { file(key).delete(); throw error }
        } finally { temp.delete() }
        return face
    }
    @Synchronized fun rename(key: String, name: String) {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean.length <= 60 && clean.none { it.isISOControl() })
        val faces = list(); require(faces.any { it.key == key })
        persist(faces.map { if (it.key == key) it.copy(name = clean) else it })
        InstalledFacePreviewStore(context).renameIfPresent(key, clean)
    }
    @Synchronized fun delete(key: String) {
        val faces = list(); require(faces.any { it.key == key })
        // Migrate b32 installations before removing their only local image source.
        if (InstalledFaceSources(context).hasSource(key)) {
            val face = faces.first { it.key == key }
            InstalledFacePreviewStore(context).prepare(binary(key), key, face.name, face.styles)
        }
        // Metadata first: a failed commit leaves the original library intact.
        persist(faces.filterNot { it.key == key })
        file(key).delete()
    }
    @Synchronized fun binary(key: String): ByteArray {
        require(list().any { it.key == key })
        val source = file(key)
        require(source.length() in 32..FaceBinValidator.MAX_BIN_BYTES.toLong())
        val bytes = source.readBytes()
        require(digest(bytes) == key) { "Watchface file changed" }
        // Keep the original content identity; installation prepares a separate repaired copy.
        FaceBinValidator.validateLocal(bytes)
        return bytes
    }
    fun preview(key: String, style: Int = 0): Bitmap? = runCatching {
        val frames = FacePreviewDecoder.decode(binary(key))
        val frame = frames.getOrNull(style) ?: frames.first()
        Bitmap.createBitmap(frame.argb, frame.width, frame.height, Bitmap.Config.ARGB_8888)
    }.getOrNull()
    private fun file(key: String): File { require(KEY.matches(key)); return File(root, "$key.bin") }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun persist(faces: List<CustomFace>) {
        val json = JSONArray().apply { faces.forEach { face -> put(JSONObject()
            .put("key", face.key).put("name", face.name).put("faceId", face.faceId)
            .put("styles", JSONArray(face.styles)).put("bytes", face.bytes).put("addedAt", face.addedAt)) } }
        val output = index.startWrite()
        try { output.write(json.toString().toByteArray()); index.finishWrite(output) }
        catch (error: Exception) { index.failWrite(output); throw error }
    }
    companion object { private val KEY = Regex("[0-9a-f]{64}") }
}
