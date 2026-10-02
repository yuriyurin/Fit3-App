package io.github.yuriyurin.fit3companion.protocol

import org.junit.Assert.*
import org.junit.Test

class FaceInstallDeadlineTest {
    @Test fun blockedConnectOrWriteHasBoundedLifetime() {
        val deadline = FaceInstallDeadline(100)
        assertFalse(deadline.expired(30_099))
        assertTrue(deadline.expired(30_100))
    }
    @Test fun realProgressExtendsIdleLimitButNotOverallLimit() {
        val deadline = FaceInstallDeadline(0)
        for (now in 20_000L..160_000L step 20_000L) {
            deadline.progress(now)
            assertFalse(deadline.expired(now + 9_000))
        }
        assertTrue(deadline.expired(180_000))
    }
    @Test fun missingAckOrMissingFullListExpiresEvenWithLateProgress() {
        val deadline = FaceInstallDeadline(0)
        deadline.progress(20_000)
        deadline.awaitConfirmation(21_000)
        deadline.awaitConfirmation(30_000)
        deadline.progress(35_000)
        assertFalse(deadline.expired(35_999))
        assertTrue(deadline.expired(36_000))
    }
    @Test fun nextInstallStartsWithFreshDeadline() {
        assertTrue(FaceInstallDeadline(0).expired(30_000))
        assertFalse(FaceInstallDeadline(30_000).expired(30_000))
    }
    @Test fun onlyVerifiedSameBinIsHiddenFromLocalGrid() {
        val faces = listOf(Fit3SapCodec.InstalledFace(201, 0, null, true))
        val sources = mapOf((201 to 0) to "ice-sha")
        assertTrue(Fit3FacePolicy.localCopyIsInstalled(201, "ice-sha", listOf(0, 1), faces, sources))
        assertFalse(Fit3FacePolicy.localCopyIsInstalled(201, "different-sha", listOf(0), faces, sources))
        assertFalse(Fit3FacePolicy.localCopyIsInstalled(200, "ice-sha", listOf(0), faces, sources))
        assertFalse(Fit3FacePolicy.localCopyIsInstalled(201, "ice-sha", listOf(1), faces, sources))
        assertFalse(Fit3FacePolicy.localCopyIsInstalled(201, "ice-sha", listOf(0), faces, emptyMap()))
        assertFalse(Fit3FacePolicy.localCopyIsInstalled(201, "ice-sha", listOf(0), emptyList(), sources))
    }
}
