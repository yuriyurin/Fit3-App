package io.github.yuriyurin.fit3companion.protocol

/** Quiet period is response-driven, not an unconditional 'success after 2.5 seconds'. */
class Fit3RefreshSession {
    enum class Result { WAITING, SUCCESS, TIMEOUT }
    private var startedAt: Long? = null
    private var confirmedAt: Long? = null
    val active: Boolean get() = startedAt != null
    fun begin(now: Long) { startedAt = now; confirmedAt = null }
    fun confirm(now: Long) { if (active) confirmedAt = now }
    fun reset() { startedAt = null; confirmedAt = null }
    fun poll(now: Long): Result {
        val start = startedAt ?: return Result.WAITING
        if (confirmedAt?.let { now - it >= 4_000 } == true) { reset(); return Result.SUCCESS }
        // A long exchange that is still returning batches must not time out merely
        // because 45 seconds have elapsed since the original request.
        if (confirmedAt == null && now - start >= 45_000) { reset(); return Result.TIMEOUT }
        return Result.WAITING
    }
}
