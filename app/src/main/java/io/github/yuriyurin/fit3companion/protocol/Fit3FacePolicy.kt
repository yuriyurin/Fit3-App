package io.github.yuriyurin.fit3companion.protocol

enum class FaceDeleteState { IDLE, CHECKING, SENDING, VERIFYING, DELETED, FAILED, TIMEOUT }

object Fit3FacePolicy {
    /** Hide only a proven identical BIN, never another file sharing the same ID. */
    fun localCopyIsInstalled(id: Int, key: String, styles: List<Int>,
        faces: List<Fit3SapCodec.InstalledFace>, sources: Map<Pair<Int, Int>, String>): Boolean =
        faces.any { it.id == id && it.sampler in styles && sources[it.id to it.sampler] == key }

    fun canDelete(faces: List<Fit3SapCodec.InstalledFace>, id: Int, sampler: Int,
        currentId: Int?, currentSampler: Int, ready: Boolean): Boolean =
        ready && id in 1..255 && currentId != null && faces.size > 1 &&
            faces.any { it.id == id && it.sampler == sampler && !it.current } &&
            (id != currentId || sampler != currentSampler)

    fun hasRoom(faces: List<Fit3SapCodec.InstalledFace>, maximum: Int?, id: Int, sampler: Int): Boolean =
        maximum != null && maximum > 0 &&
            (faces.any { it.id == id && it.sampler == sampler } || faces.size < maximum)

    fun deletionConfirmed(target: Pair<Int, Int>, state: FaceDeleteState,
        faces: List<Fit3SapCodec.InstalledFace>): Boolean =
        state == FaceDeleteState.VERIFYING && faces.none { (it.id to it.sampler) == target }
}
