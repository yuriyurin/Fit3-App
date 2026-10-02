package io.github.yuriyurin.fit3companion

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import io.github.yuriyurin.fit3companion.ble.CustomFaceLibrary
import io.github.yuriyurin.fit3companion.ble.InstalledFacePreviewStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val LocalCustomFaceLibrary = staticCompositionLocalOf<CustomFaceLibrary?> { null }
internal val LocalInstalledFaceKeys = staticCompositionLocalOf<Map<Pair<Int, Int>, String>> { emptyMap() }
internal val LocalCurrentFaceSampler = staticCompositionLocalOf { 0 }

/** A known BIN whose local file is missing must not fall back to unrelated stock artwork. */
internal data class InstalledPreview(val custom: Boolean, val bitmap: Bitmap?)

@Composable
internal fun installedFaceName(key: String?): String? {
    if (key == null) return null
    return LocalCustomFaceLibrary.current?.list()?.firstOrNull { it.key == key }?.name
        ?: InstalledFacePreviewStore(LocalContext.current).name(key)
}

@Composable
internal fun installedPreview(id: Int?, sampler: Int): InstalledPreview {
    val key = LocalInstalledFaceKeys.current[id to sampler]
    val library = LocalCustomFaceLibrary.current
    val context = LocalContext.current
    val store = remember(context) { InstalledFacePreviewStore(context) }
    val bitmap by produceState<Bitmap?>(null, key, sampler, library, store) {
        value = if (key != null) withContext(Dispatchers.IO) {
            store.preview(key, sampler) ?: runCatching {
                val source = library?.list()?.firstOrNull { it.key == key } ?: return@runCatching null
                store.prepare(library.binary(key), key, source.name, listOf(sampler))
                store.preview(key, sampler)
            }.getOrNull()
        } else null
    }
    return InstalledPreview(key != null, bitmap)
}
