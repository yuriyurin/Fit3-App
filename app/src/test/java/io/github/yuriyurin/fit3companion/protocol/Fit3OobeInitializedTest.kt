package io.github.yuriyurin.fit3companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Fit3OobeInitializedTest {
    private fun response(flag: Byte) = byteArrayOf(0x41, 1) + ByteArray(12) +
        byteArrayOf(2, 3, 'F'.code.toByte(), 'i'.code.toByte(), 't'.code.toByte(),
            3) + ByteArray(7) + byteArrayOf(4, flag)

    @Test fun readsInitializationState() {
        assertEquals(false, Fit3OobeCodec.isInitialized(response(0)))
        assertEquals(true, Fit3OobeCodec.isInitialized(response(1)))
        assertNull(Fit3OobeCodec.isInitialized(response(2)))
        assertNull(Fit3OobeCodec.isInitialized(byteArrayOf(0x41, 1)))
    }
}
