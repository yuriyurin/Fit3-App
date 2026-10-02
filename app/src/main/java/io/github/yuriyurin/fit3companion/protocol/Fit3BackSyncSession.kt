package io.github.yuriyurin.fit3companion.protocol

/** Coalesces snapshots. No unbounded replay loop when a watch does not acknowledge. */
class Fit3BackSyncSession {
    private var attempted: List<Fit3PedometerBackSync.Bin>? = null
    private var acknowledged: List<Fit3PedometerBackSync.Bin>? = null
    private var pendingSequence: Int? = null
    private var attemptedAt = 0L
    private var pendingDay: Long? = null
    val pending: Boolean get() = pendingSequence != null

    fun reset() { attempted = null; acknowledged = null; pendingSequence = null; attemptedAt = 0; pendingDay = null }

    fun begin(bins: List<Fit3PedometerBackSync.Bin>, day: Long, sequence: Int, now: Long): Boolean {
        if (pendingDay != null && pendingDay != day) reset()
        if (bins.isEmpty() || bins == acknowledged) return false
        if (pending || attempted == bins && now - attemptedAt < 60_000 ||
            attempted != null && now - attemptedAt < 5_000) return false
        attempted = bins.toList(); pendingDay = day; pendingSequence = sequence; attemptedAt = now
        return true
    }

    fun delivered(sequence: Int): Boolean {
        if (pendingSequence != sequence) return false
        acknowledged = attempted; pendingSequence = null
        return true
    }

    // Other firmware may respond, but AZA3's 0c11bb5c handler does not emit C7.
    fun receive(message: ByteArray): Boolean =
        Fit3PedometerBackSync.responseSequence(message)?.let(::delivered) ?: false

    fun expire(sequence: Int) { if (pendingSequence == sequence) pendingSequence = null }
}
