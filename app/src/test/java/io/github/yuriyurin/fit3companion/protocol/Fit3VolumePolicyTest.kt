package io.github.yuriyurin.fit3companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Fit3VolumePolicyTest {
    @Test fun sliderCanMoveBothDirectionsOnLargeOemScale() {
        assertEquals(1, Fit3VolumePolicy.stepToward(40, 160, 50))
        assertEquals(-1, Fit3VolumePolicy.stepToward(40, 160, 30))
        assertEquals(0, Fit3VolumePolicy.stepToward(40, 160, 40))
    }

    @Test fun invalidWatchValueNeverRaisesVolume() {
        assertNull(Fit3VolumePolicy.stepToward(40, 160, 9999))
        assertNull(Fit3VolumePolicy.stepToward(40, 0, 1))
        assertNull(Fit3VolumePolicy.stepToward(40, 160, -1))
    }
}
