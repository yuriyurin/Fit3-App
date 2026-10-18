package io.github.yuriyurin.fit3companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Fit3OobeInitializedTest {
    private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // Captured WatchInfo reply (vendor app, R390XXU0AZA3), cut after param 5.
    private fun response(flag: Int) = ("41" + "010c" + "R390XXU0AZA3".ascii() + "020b" + "Galaxy Fit3".ascii() +
        "03" + "SM-R390".ascii() + "04" + "%02x".format(flag) + "05" + "R7AX30N9QQE".ascii()).hex()

    private fun String.ascii() = toByteArray(Charsets.US_ASCII).joinToString("") { "%02x".format(it) }

    @Test fun readsInitializationState() {
        assertEquals(false, Fit3OobeCodec.isInitialized(response(0)))
        assertEquals(true, Fit3OobeCodec.isInitialized(response(1)))
        assertNull(Fit3OobeCodec.isInitialized(response(2)))
        assertNull(Fit3OobeCodec.isInitialized(byteArrayOf(0x41, 1)))
    }
}
