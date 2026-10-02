package io.github.yuriyurin.fit3companion.ble

import android.content.Context
import io.github.yuriyurin.fit3companion.protocol.Fit3SapCodec
import org.json.JSONObject
import java.util.Locale

/** A face ID is not a content identity. Bind the exact imported SHA only after installation
 * ACK + fresh watch list. A verified transmission copy may repair Studio metadata while
 * retaining this original SHA for artwork/name ownership. Never infer a local source by ID.
 */
internal class InstalledFaceSources(context: Context) {
    private val prefs = context.getSharedPreferences("fit3_face_sources", Context.MODE_PRIVATE)
    private fun address(device: String) = device.uppercase(Locale.ROOT)
    private fun read(device: String) = runCatching {
        JSONObject(prefs.getString(address(device), "{}")!!)
    }.getOrElse { JSONObject() }
    private fun write(device: String, data: JSONObject) {
        check(prefs.edit().putString(address(device), data.toString()).commit())
    }
    @Synchronized fun hasSource(key: String): Boolean = prefs.all.keys.any { device ->
        val data = read(device)
        data.keys().asSequence().any { data.optJSONObject(it)?.optString("key") == key }
    }
    @Synchronized fun confirmed(device: String, face: Fit3SapCodec.InstalledFace, customKey: String?) {
        require(device.isNotBlank())
        val data = read(device)
        val pair = "${face.id}/${face.sampler}"
        if (customKey == null) data.remove(pair) // Installing the official package replaces the BIN.
        else {
            require(customKey.matches(Regex("[a-f0-9]{64}")))
            data.put(pair, JSONObject().put("key", customKey).put("version", face.version ?: ""))
        }
        write(device, data)
    }
    @Synchronized fun reconcile(device: String, faces: List<Fit3SapCodec.InstalledFace>): Map<Pair<Int, Int>, String> {
        if (device.isBlank()) return emptyMap()
        val data = read(device)
        val result = linkedMapOf<Pair<Int, Int>, String>()
        val active = faces.associateBy { "${it.id}/${it.sampler}" }
        var changed = false
        data.keys().asSequence().toList().forEach { pair ->
            val record = data.optJSONObject(pair)
            val face = active[pair]
            val key = record?.optString("key").orEmpty()
            if (face == null || record == null || !key.matches(Regex("[a-f0-9]{64}")) ||
                record.optString("version") != (face.version ?: "")) {
                data.remove(pair); changed = true
            } else result[face.id to face.sampler] = key
        }
        if (changed) write(device, data)
        return result
    }
}
