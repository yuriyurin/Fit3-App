package io.github.yuriyurin.fit3companion.protocol

/** Experimental high-ID packages are applied by the file receiver itself. No truncated
 * INSTALL/SET/DELETE is sent. Its byte ACK is only provisional until a complete transfer
 * and a fresh, full-name-identified watch list confirm the exact ID and sampler.
 */
internal class ExtendedFaceInstall(val id: Int, val sampler: Int) {
    init { require(id in 256..99999 && sampler == 0) }
    // AZA3 FUN_0c111438 incorrectly finds the first '0' after the first '_',
    // copies at most five characters and parses decimal. For 99999 it reaches
    // "02.bin" in the resolution suffix and ACKs ID 2. This ACK is provisional:
    // the client still requires a fresh watch list with the exact full identity.
    internal val automaticAckId: Int = automaticAckId(id)
    private var transferCompleted = false
    private var status: Int? = null
    fun response(result: Fit3SapCodec.InstallFaceResult) {
        if (result.id in setOf(id and 255, automaticAckId) && result.sampler == sampler && status == null)
            status = result.status
    }
    fun transferred() { transferCompleted = true }
    val statusAfterTransfer: Int? get() = if (transferCompleted) status else null

    companion object {
        internal fun automaticAckId(id: Int): Int {
            require(id in 1..99999)
            val suffix = "SM-R390_${id.toString().padStart(5, '0')}_256x402.bin".substringAfter('_')
            val zero = suffix.indexOf('0')
            return suffix.substring(zero).take(5).takeWhile { it in '0'..'9' }
                .toInt().and(255)
        }
    }
}
