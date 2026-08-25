package com.ynk.virtualdisplay.ui.display

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Software cursor state used by desktop mode.
 *
 * Android's system mouse pointer is a separate SurfaceFlinger layer and is not
 * included in the H.264 screen capture. The WebRTC browser therefore needs a
 * lightweight software cursor overlay. This object is the process-local source
 * of truth for that overlay.
 */
object DesktopCursorState {
    private val visible = AtomicBoolean(false)
    private val x = AtomicInteger(960)
    private val y = AtomicInteger(540)
    private val width = AtomicInteger(1920)
    private val height = AtomicInteger(1080)

    fun reset(w: Int = 1920, h: Int = 1080) {
        width.set(w.coerceAtLeast(1))
        height.set(h.coerceAtLeast(1))
        x.set(w.coerceAtLeast(1) / 2)
        y.set(h.coerceAtLeast(1) / 2)
        visible.set(true)
    }

    fun update(cursorX: Float, cursorY: Float, w: Int, h: Int) {
        width.set(w.coerceAtLeast(1))
        height.set(h.coerceAtLeast(1))
        x.set(cursorX.coerceIn(0f, w.toFloat()).toInt())
        y.set(cursorY.coerceIn(0f, h.toFloat()).toInt())
        visible.set(true)
    }

    fun hide() {
        visible.set(false)
    }

    fun json(): String = "{\"visible\":${visible.get()},\"x\":${x.get()},\"y\":${y.get()},\"width\":${width.get()},\"height\":${height.get()}}"
}
