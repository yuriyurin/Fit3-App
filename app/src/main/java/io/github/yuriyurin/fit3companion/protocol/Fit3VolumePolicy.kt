package io.github.yuriyurin.fit3companion.protocol

/** Reject malformed watch values and choose one safe step toward a requested volume. */
object Fit3VolumePolicy {
    fun stepToward(current: Int, max: Int, requested: Int): Int? {
        if (max <= 0 || current !in 0..max || requested !in 0..max) return null
        return requested.compareTo(current)
    }
}
