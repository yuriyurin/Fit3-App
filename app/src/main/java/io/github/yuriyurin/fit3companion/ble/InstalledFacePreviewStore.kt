package io.github.yuriyurin.fit3companion.ble

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Durable display copies, NOT temporary cache and NOT the BIN library. Identity remains
 * the verified content SHA + sampler; watch/device ownership lives in InstalledFaceSources.
 * Removing a local BIN must never remove the image of an installed watch face.
 */
internal class InstalledFacePreviewStore(context: Context) {
    private val root = File(context.filesDir, "installed-face-previews")

    fun prepare(bytes: ByteArray, key: String, name: String, styles: List<Int>) = synchronized(LOCK) {
        require(MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) } == key)
        val report = FaceBinValidator.validateLocal(bytes)
        require(styles.isNotEmpty() && styles.all { it in report.styles })
        val frames = FacePreviewDecoder.decode(bytes)
        check(root.mkdirs() || root.isDirectory)
        styles.distinct().forEach { style ->
            val frame = frames.getOrNull(style) ?: frames.first()
            val bitmap = Bitmap.createBitmap(frame.argb, frame.width, frame.height, Bitmap.Config.ARGB_8888)
            try {
                write(imageFile(key, style)) { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
            } finally { bitmap.recycle() }
        }
        writeName(key, name)
    }

    fun preview(key: String, style: Int): Bitmap? = synchronized(LOCK) {
        runCatching {
            val bytes = imageFile(key, style).openRead().use { stream ->
                require(stream.channel.size() <= 2 * 1024 * 1024)
                stream.readBytes()
            }
            require(bytes.size <= 2 * 1024 * 1024)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..512 && bounds.outHeight in 1..512)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    fun name(key: String): String? = synchronized(LOCK) {
        runCatching {
            JSONObject(nameFile(key).openRead().use {
                require(it.channel.size() <= 4096)
                it.readBytes().toString(Charsets.UTF_8)
            })
                .getString("name").takeIf { it.isNotBlank() && it.length <= 60 }
        }.getOrNull()
    }

    fun renameIfPresent(key: String, name: String) = synchronized(LOCK) {
        if (nameFile(key).baseFile.exists()) writeName(key, name)
    }

    private fun writeName(key: String, name: String) {
        require(name.isNotBlank() && name.length <= 60 && name.none { it.isISOControl() })
        write(nameFile(key)) { it.write(JSONObject().put("name", name).toString().toByteArray(Charsets.UTF_8)) }
    }
    private fun nameFile(key: String): AtomicFile {
        require(KEY.matches(key)); return AtomicFile(File(root, "$key.json"))
    }
    private fun imageFile(key: String, style: Int): AtomicFile {
        require(KEY.matches(key) && style in 0..255)
        return AtomicFile(File(root, "$key-$style.png"))
    }
    private fun write(file: AtomicFile, block: (java.io.FileOutputStream) -> Unit) {
        val output = file.startWrite()
        try { block(output); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
    companion object {
        private val KEY = Regex("[0-9a-f]{64}")
        private val LOCK = Any()
    }
}
