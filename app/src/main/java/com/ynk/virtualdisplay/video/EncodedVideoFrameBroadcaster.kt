package com.ynk.virtualdisplay.video

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Zero-copy on the normal path: subscribers receive the same short-lived frame
 * buffer that is consumed by MediaCodec. A subscriber may copy it when it needs
 * to keep the frame asynchronously.
 */
class EncodedVideoFrameBroadcaster {
    private val sinks = CopyOnWriteArraySet<EncodedVideoSink>()

    fun add(sink: EncodedVideoSink) {
        sinks.add(sink)
    }

    fun remove(sink: EncodedVideoSink) {
        sinks.remove(sink)
    }

    fun clear() {
        sinks.clear()
    }

    fun isEmpty(): Boolean = sinks.isEmpty()

    fun publishFormat(codec: String, width: Int, height: Int) {
        if (sinks.isEmpty()) return
        sinks.forEach { sink ->
            runCatching { sink.onFormat(codec, width, height) }
        }
    }

    fun publishFrame(
        ptsUs: Long,
        isConfig: Boolean,
        isKeyFrame: Boolean,
        data: ByteArray,
        size: Int
    ) {
        if (sinks.isEmpty()) return
        sinks.forEach { sink ->
            runCatching { sink.onFrame(ptsUs, isConfig, isKeyFrame, data, size) }
        }
    }
}
