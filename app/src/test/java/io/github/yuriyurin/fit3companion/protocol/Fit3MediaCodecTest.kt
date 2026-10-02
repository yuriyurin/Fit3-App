package io.github.yuriyurin.fit3companion.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class Fit3MediaCodecTest {
    // Independent wire fixtures from the stock SAMessageData.Builder (format=0,
    // setNumberOfParam > 0), not assembled with our own serialization helpers.
    @Test fun capabilityMatchesStockFixedResponseWithParameterCount() {
        assertArrayEquals(byteArrayOf(
            0x54, 2, 1, 0xA0.toByte(), 0, 0, 0, 2, 0x6A, 0, 0, 0,
        ), Fit3MediaCodec.capabilityResponse(160, 106))
        assertArrayEquals(byteArrayOf(
            0x54, 2, 1, 15, 0, 0, 0, 2, 10, 0, 0, 0,
        ), Fit3MediaCodec.capabilityResponse(15, 10))
    }

    @Test fun volumeInfoMatchesStockFixedResponseWithParameterCount() {
        assertArrayEquals(byteArrayOf(
            0x55, 2, 1, 80, 0, 0, 0, 2, 2,
        ), Fit3MediaCodec.volumeInfoResponse(80, connected = true))
        assertArrayEquals(byteArrayOf(
            0x55, 2, 1, 0, 0, 0, 0, 2, 1,
        ), Fit3MediaCodec.volumeInfoResponse(0, connected = false))
    }

    @Test fun allWatchScreenStatesMatchStockFixedResponseWithParameterCount() {
        for (state in 0..2) {
            assertArrayEquals(byteArrayOf(0x57, 1, 1, state.toByte()),
                Fit3MediaCodec.mediaChangedResponse(state))
        }
    }

    @Test fun mediaTransferNamesMatchStockProviderHashScheme() {
        val queue = Fit3MediaCodec.mediaFileName("com.spotify.music", "Очередь", null,
            "que", 1_700_000_000_000L)
        val art = Fit3MediaCodec.mediaFileName("com.spotify.music", "Автор", "Песня",
            "art", 1_700_000_000_000L)
        assertTrue(queue.matches(Regex("[0-9a-f]{1,32}\\.que")))
        assertTrue(art.matches(Regex("[0-9a-f]{1,32}\\.art")))
        assertTrue(queue != art)
        assertEquals(queue, Fit3MediaCodec.mediaFileName("com.spotify.music", "Очередь", null,
            "que", 1_700_000_000_000L))
    }

    @Test fun volumeControlUsesSingleByteActionAndValue() {
        assertEquals(Fit3MediaCodec.VolumeCommand(Fit3MediaCodec.VolumeAction.SET, 7),
            Fit3MediaCodec.parseVolumeControl(byteArrayOf(7, 1, 1, 2, 7)))
        assertEquals(Fit3MediaCodec.VolumeCommand(Fit3MediaCodec.VolumeAction.UP),
            Fit3MediaCodec.parseVolumeControl(byteArrayOf(7, 1, 2)))
        assertEquals(Fit3MediaCodec.VolumeCommand(Fit3MediaCodec.VolumeAction.UP, 1),
            Fit3MediaCodec.parseVolumeControl(byteArrayOf(7, 1, 2, 2, 1, 0, 0, 0)))
        assertEquals(Fit3MediaCodec.VolumeCommand(Fit3MediaCodec.VolumeAction.SET, 80),
            Fit3MediaCodec.parseVolumeControl(byteArrayOf(7, 1, 1, 2, 80, 0, 0, 0)))
        assertEquals(Fit3MediaCodec.VolumeCommand(Fit3MediaCodec.VolumeAction.DOWN),
            Fit3MediaCodec.parseVolumeControl(byteArrayOf(7, 1, 3)))
    }

    @Test fun skipItemRequestUsesFixedStringParameters() {
        val app = "org.player".toByteArray()
        val request = byteArrayOf(16, 1, app.size.toByte()) + app + byteArrayOf(2, 2, 52, 50)
        assertEquals("org.player" to 42L, Fit3MediaCodec.skipToItemRequest(request))
    }

    @Test fun queueRequestIncludesVariableHeaderAndAppId() {
        val app = "org.example.player".toByteArray()
        val actualWatchRequest = byteArrayOf(9, 1, app.size.toByte()) + app
        assertEquals("org.example.player", Fit3MediaCodec.requestAppId(actualWatchRequest,
            Fit3MediaCodec.REQ_QUEUE))
        val request = byteArrayOf(0x89.toByte(), 1, 1, app.size.toByte()) + app
        assertEquals("org.example.player", Fit3MediaCodec.requestAppId(request, Fit3MediaCodec.REQ_QUEUE))
        assertNull(Fit3MediaCodec.requestAppId(byteArrayOf(0x89.toByte(), 1, 1, 50),
            Fit3MediaCodec.REQ_QUEUE))
    }

    @Test fun queueWireResponseAndFileCarrySameFilename() {
        val queue = Fit3MediaCodec.QueueData("org.example.player", "Мой список",
            listOf(Fit3MediaCodec.QueueItem(42, "Трек", "Артист")))
        val response = Fit3MediaCodec.queueResponse(queue, "test.que")
        val encodedFile = Fit3MediaCodec.queueFile(queue, "test.que")
        val file = Base64.getDecoder().decode(encodedFile)
        assertEquals(0xd9, response[0].toInt() and 0xff)
        assertEquals(4, response[1].toInt() and 0xff)
        assertEquals(0xd9, file[0].toInt() and 0xff)
        assertEquals(5, file[1].toInt() and 0xff)
        assertArrayEquals(response.copyOfRange(2, response.size), file.copyOfRange(2, response.size))
        assertArrayEquals(byteArrayOf(5, 3, 2, 52, 50),
            file.copyOfRange(response.size, response.size + 5))
        assertTrue(encodedFile.all { it.toInt().toChar() in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=" })
    }

    @Test fun queueFileAndAnnouncementAreBothLimitedToTwentyTracks() {
        val queue = Fit3MediaCodec.QueueData("org.example.player", "Queue",
            (1L..22L).map { Fit3MediaCodec.QueueItem(it, "Track $it", "Artist") })
        val response = Fit3MediaCodec.queueResponse(queue, "test.que")
        val file = Base64.getDecoder().decode(Fit3MediaCodec.queueFile(queue, "test.que"))
        assertEquals(24, file[1].toInt() and 0xff)
        assertTrue(file.toString(Charsets.UTF_8).contains("Track 20"))
        assertFalse(file.toString(Charsets.UTF_8).contains("Track 21"))
        assertArrayEquals(response.copyOfRange(2, response.size), file.copyOfRange(2, response.size))
        assertTrue(response.toString(Charsets.UTF_8).contains("test.que"))
    }

    @Test fun metadataDeclaresArtworkAndPlayer() {
        val state = Fit3MediaCodec.MediaState(3, 0, 90_000, "Песня", "Автор", "org.player")
        val bytes = Fit3MediaCodec.metadataResponse(state, "cover.art")
        assertEquals(0xd1, bytes[0].toInt() and 0xff)
        assertEquals(5, bytes[1].toInt() and 0xff)
        val text = bytes.toString(Charsets.UTF_8)
        assertTrue(text.contains("cover.art"))
        assertTrue(text.contains("org.player"))
    }

    @Test fun playerAvailabilityMatchesSamsungAgentNineFormat() {
        val present = Fit3MediaCodec.appCountChangedResponse("com.spotify.music")
        assertEquals(0xd8, present[0].toInt() and 0xff)
        assertEquals(2, present[1].toInt() and 0xff)
        assertArrayEquals(byteArrayOf(1, 1, 2, 17), present.copyOfRange(2, 6))
        assertTrue(present.toString(Charsets.UTF_8).contains("com.spotify.music"))
        val absent = Fit3MediaCodec.appCountChangedResponse("")
        assertArrayEquals(byteArrayOf(0xd8.toByte(), 2, 1, 0, 2, 0), absent)
    }

    @Test fun mediaChangedEchoesWatchStateAndStoppedPlayback() {
        val request = byteArrayOf(1, 1, 2)
        assertEquals(2, Fit3MediaCodec.mediaChangedRequestState(request))
        assertArrayEquals(byteArrayOf(0x57, 1, 1, 2),
            Fit3MediaCodec.mediaChangedResponse(Fit3MediaCodec.mediaChangedRequestState(request)!!))
        val stopped = Fit3MediaCodec.playbackStateResponse(
            Fit3MediaCodec.MediaState(Fit3MediaCodec.PLAYBACK_STOPPED, 0, 0, "", ""))
        assertEquals(1, stopped[3].toInt() and 0xff)
    }

    @Test fun appInfoCanDeclareSeparatePlayerIcon() {
        val bytes = Fit3MediaCodec.appInfoResponse("com.spotify.music", "Spotify",
            "com.spotify.music.app")
        assertEquals(0xd6, bytes[0].toInt() and 0xff)
        assertTrue(bytes.toString(Charsets.UTF_8).contains("com.spotify.music.app"))
    }
}
