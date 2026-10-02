package io.github.yuriyurin.fit3companion.protocol

/** Returning a reply is possible only after the commit callback succeeds.
 * A transport ACK is not a substitute for durable application-level acceptance.
 */
object Fit3HealthAcceptance {
    fun accept(previous: Fit3HealthCodec.State, payload: ByteArray, now: Long,
               source: String? = null,
               commit: (Fit3HealthCodec.State, ByteArray) -> Unit): Fit3HealthCodec.Incoming {
        val changedSource = source != null && !source.equals(previous.stepSourceAddress, ignoreCase = true)
        val base = if (changedSource) previous.copy(steps = null, walkSteps = null, runSteps = null,
            distanceMeters = null, activeCalories = null, activeMinutes = null,
            stepHistory = emptyList(), stepRecords = emptyMap(), stepDay = null,
            stepsPartial = false, stepSourceAddress = source) else previous
        val incoming = Fit3HealthCodec.handle(base, payload, now)
        val accepted = incoming.reply != null && incoming.event == Fit3HealthCodec.Event.SYNC_DATA
        val result = if (accepted) incoming.copy(state = incoming.state.copy(lastSuccessfulSyncMillis = now))
            else incoming
        commit(result.state, payload)
        return result
    }
}
