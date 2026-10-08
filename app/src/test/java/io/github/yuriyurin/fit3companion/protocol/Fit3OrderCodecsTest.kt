package io.github.yuriyurin.fit3companion.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Fit3OrderCodecsTest {
    private fun String.hex(): ByteArray = replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun appsSetMatchesVendorCapture() {
        // samsung_sort_tiles_apps_buttons.log frame 1103: positions are 0-based.
        assertArrayEquals(("01 02 01 03 0a 00 01 01 00 00 02 01 01 00 03 01 02 00 04 01 03 00 05 01 04" +
            " 00 07 01 05 00 08 01 06 00 09 01 07 00 0c 01 08 00 06 01 09").hex(),
            Fit3AppsCodec.set(listOf(1, 2, 3, 4, 5, 7, 8, 9, 12, 6)))
    }

    @Test fun bandAppsTableIsOneBased() {
        // Same capture, frame 839.
        val table = ("c0 02 01 03 0a 00 01 01 01 00 02 01 02 00 03 01 03 00 04 01 04 00 05 01 05" +
            " 00 07 01 06 00 08 01 07 00 09 01 08 00 0c 01 09 00 06 01 0a").hex()
        assertEquals(listOf(1, 2, 3, 4, 5, 7, 8, 9, 12, 6), Fit3AppsCodec.parse(table)!!.map { it.id })
        assertEquals("Samsung Health", Fit3AppsCodec.names[1])
    }

}
