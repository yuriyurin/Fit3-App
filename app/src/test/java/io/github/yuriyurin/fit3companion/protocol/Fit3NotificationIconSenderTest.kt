package io.github.yuriyurin.fit3companion.protocol

import org.junit.Assert.*
import org.junit.Test

class Fit3NotificationIconSenderTest {
    private fun ack(version: Int, success: Boolean) =
        byteArrayOf(62, ((version shl 3) or if (success) 1 else 0).toByte(), 12, 0, 0, 0)

    @Test fun rgba52FitsStockChunkEnvelopeAndReassemblesExactly() {
        val bytes = ByteArray(52 * 52 * 4 + 22) { (it % 251).toByte() }
        val sender = Fit3NotificationIconSender()
        val batch = requireNotNull(sender.enqueue("icon", bytes).batch)
        assertEquals(12, batch.packets.size)
        batch.packets.forEachIndexed { index, packet ->
            assertTrue(packet.size <= 980)
            assertEquals(63, packet[0].toInt())
            assertEquals(index, SaMessageCodec.readLeInt(packet, 2))
            assertEquals(batch.version, (packet[1].toInt() and 0xff) ushr 3)
            assertEquals(if (index == 0) 0 else if (index == 11) 2 else 1, packet[1].toInt() and 3)
            Fit3SapCodec.encodeMessage(Fit3SapCodec.SERVICE_NOTIFICATIONS, packet, 500)
        }
        assertArrayEquals(bytes, batch.packets.flatMap { it.drop(6) }.toByteArray())
        assertEquals("icon", sender.acknowledge(ack(batch.version, true)).acceptedKey)
    }

    @Test fun rgba112RetainsAllPixelsAndOldTransmissionStopsAfterReset() {
        val bytes = ByteArray(112 * 112 * 4 + 22) { (it % 251).toByte() }
        val sender = Fit3NotificationIconSender()
        val batch = requireNotNull(sender.enqueue("large", bytes).batch)
        assertEquals(52, batch.packets.size)
        assertTrue(sender.isCurrent(batch.version))
        assertArrayEquals(bytes, batch.packets.flatMap { it.drop(6) }.toByteArray())
        assertEquals("large", sender.acknowledge(ack(batch.version, true)).acceptedKey)
        assertFalse(sender.isCurrent(batch.version))
        val next = requireNotNull(sender.enqueue("next", bytes).batch)
        sender.reset()
        assertFalse(sender.isCurrent(next.version))
    }

    @Test fun failureRetriesOnlyThreeTimesAndIgnoresStaleAck() {
        val sender = Fit3NotificationIconSender()
        val first = requireNotNull(sender.enqueue("icon", ByteArray(5000)).batch)
        assertNull(sender.acknowledge(ack((first.version + 1) % 32, true)).acceptedKey)
        val second = requireNotNull(sender.timeout(first.version).batch)
        assertNull(sender.acknowledge(ack(first.version, true)).acceptedKey)
        val third = requireNotNull(sender.acknowledge(ack(second.version, false)).batch)
        assertEquals("icon", sender.timeout(third.version).failedKey)
        assertNull(sender.timeout(third.version).batch)
    }

    @Test fun queuedIconsStartOnlyAfterMatchingAckAndResetDropsPending() {
        val sender = Fit3NotificationIconSender()
        val first = requireNotNull(sender.enqueue("a", ByteArray(5000)).batch)
        assertNull(sender.enqueue("b", ByteArray(5000)).batch)
        assertNull(sender.enqueue("b", ByteArray(5000)).batch)
        val second = sender.acknowledge(ack(first.version, true))
        assertEquals("a", second.acceptedKey)
        assertNotNull(second.batch)
        sender.reset()
        assertNull(sender.timeout(second.batch!!.version).batch)
    }
}
