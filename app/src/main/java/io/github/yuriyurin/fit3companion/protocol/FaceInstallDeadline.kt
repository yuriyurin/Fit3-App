package io.github.yuriyurin.fit3companion.protocol

/** Monotonic deadlines: never leave selection locked on connect/write/ACK stalls. */
internal class FaceInstallDeadline(private val startedAt: Long) {
    private var lastProgressAt = startedAt
    private var confirmationAt: Long? = null
    fun progress(now: Long) { if (confirmationAt == null) lastProgressAt = now }
    fun awaitConfirmation(now: Long) { if (confirmationAt == null) confirmationAt = now }
    fun expired(now: Long): Boolean = confirmationAt?.let { now - it >= 15_000 }
        ?: (now - startedAt >= 180_000 || now - lastProgressAt >= 30_000)
}
