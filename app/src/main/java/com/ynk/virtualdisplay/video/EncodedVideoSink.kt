package com.ynk.virtualdisplay.video

/**
 * Optional encoded-video fan-out point.
 *
 * The callback is invoked synchronously from the decoder input thread. The [data]
 * array is owned by the video pipeline and may be reused after [onFrame] returns;
 * consumers that need asynchronous processing MUST copy the bytes they retain.
 *
 * This is deliberately codec-agnostic at the API boundary while the current
 * implementation emits H.264 packets from scrcpy's native packet format.
 */
interface EncodedVideoSink {
    fun onFormat(codec: String, width: Int, height: Int) {}

    fun onFrame(
        ptsUs: Long,
        isConfig: Boolean,
        isKeyFrame: Boolean,
        data: ByteArray,
        size: Int
    )
}
