package com.ynk.virtualdisplay.video.output

import com.ynk.virtualdisplay.video.VideoStreamController

/** Lifecycle wrapper that keeps WebRTC output independent from the display repository. */
class WebRtcH264OutputManager(
    private val videoController: VideoStreamController
) : AutoCloseable {
    private var endpoint: WebRtcH264TcpEndpoint? = null

    @Synchronized
    fun start(displayId: Int, bindHost: String, port: Int): Result<Unit> {
        return runCatching {
            endpoint?.close()
            val newEndpoint = WebRtcH264TcpEndpoint(
                videoController = videoController,
                displayId = displayId,
                bindHost = bindHost,
                port = port
            )
            try {
                newEndpoint.start()
                endpoint = newEndpoint
            } catch (t: Throwable) {
                newEndpoint.close()
                throw t
            }
        }.map { Unit }
    }

    @Synchronized
    fun stop() {
        endpoint?.close()
        endpoint = null
    }

    @Synchronized
    fun status(): WebRtcH264OutputStatus = endpoint?.status()
        ?: WebRtcH264OutputStatus(false, -1, null, null, 0)

    @Synchronized
    override fun close() = stop()
}
