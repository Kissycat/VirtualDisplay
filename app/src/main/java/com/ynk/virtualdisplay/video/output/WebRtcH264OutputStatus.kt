package com.ynk.virtualdisplay.video.output

data class WebRtcH264OutputStatus(
    val running: Boolean,
    val displayId: Int,
    val bindHost: String?,
    val port: Int?,
    val clientCount: Int
)
