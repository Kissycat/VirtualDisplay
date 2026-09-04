package com.ynk.virtualdisplay.ui.desktop

import android.util.Log
import java.lang.ref.WeakReference

/** Routes the existing desktop cursor stream into the window whose content
 * rectangle is currently under the cursor. The main virtual display remains
 * the fallback target, so cursor movement outside a desktop window is handled
 * exactly as before. */
internal object DesktopWindowInputRouter {
    private const val TAG = "DesktopWindowRouter"

    data class Entry(
        val session: DesktopWindowSession,
        val displayId: Int,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val chromeTop: Int,
    ) {
        fun containsContent(px: Float, py: Float): Boolean =
            width > 0 && height > 0 &&
                px >= x && px < x + width &&
                py >= y + chromeTop && py < y + chromeTop + height

        fun mapPoint(px: Float, py: Float): Pair<Int, Int> {
            val localX = (px - x).coerceIn(0f, width.toFloat())
            val localY = (py - y - chromeTop).coerceIn(0f, height.toFloat())
            return session.mapContentPointToVideo(localX.toInt(), localY.toInt(), width, height)
        }
    }

    private val lock = Any()
    private val entries = LinkedHashMap<DesktopWindowSession, Entry>()
    private var pointerTarget: WeakReference<DesktopWindowSession>? = null

    fun register(
        session: DesktopWindowSession,
        displayId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        chromeTop: Int,
    ) {
        synchronized(lock) {
            entries[session] = Entry(session, displayId, x, y, width, height, chromeTop)
        }
        Log.d(TAG, "register sessionDisplay=${session.windowDisplayId()} parentDisplay=$displayId rect=$x,$y ${width}x$height chromeTop=$chromeTop")
    }

    fun update(
        session: DesktopWindowSession,
        displayId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        chromeTop: Int,
    ) {
        synchronized(lock) {
            if (entries.containsKey(session)) {
                entries[session] = Entry(session, displayId, x, y, width, height, chromeTop)
            }
        }
    }

    fun bringToFront(session: DesktopWindowSession) {
        synchronized(lock) {
            val e = entries.remove(session) ?: return
            entries[session] = e
            pointerTarget = WeakReference(session)
        }
        Log.d(TAG, "front sessionDisplay=${session.windowDisplayId()}")
    }

    fun unregister(session: DesktopWindowSession) {
        synchronized(lock) {
            entries.remove(session)
            if (pointerTarget?.get() === session) pointerTarget = null
        }
        Log.d(TAG, "unregister sessionDisplay=${session.windowDisplayId()}")
    }

    private fun findAt(displayId: Int, x: Float, y: Float): Entry? = synchronized(lock) {
        entries.values.asSequence().filter { it.displayId == displayId && it.containsContent(x, y) }.lastOrNull()
    }

    suspend fun injectPointer(
        displayId: Int,
        action: Int,
        x: Float,
        y: Float,
        actionButton: Int,
        buttons: Int,
        pointerId: Long,
        fallbackWidth: Int,
        fallbackHeight: Int,
        latch: Boolean,
    ): Boolean {
        val target = synchronized(lock) {
            val latched = pointerTarget?.get()?.let { entries[it] }
            when {
                latch && action == android.view.MotionEvent.ACTION_DOWN -> findAt(displayId, x, y)
                latch && latched != null && action != android.view.MotionEvent.ACTION_HOVER_MOVE && action != android.view.MotionEvent.ACTION_HOVER_ENTER -> latched
                else -> findAt(displayId, x, y)
            }
        }
        if (target == null) return false

        if (action == android.view.MotionEvent.ACTION_DOWN) {
            synchronized(lock) { pointerTarget = WeakReference(target.session) }
        }

        val (mappedX, mappedY) = target.mapPoint(x, y)
        val (iw, ih) = target.session.inputCoordinateSize(target.width, target.height)
        Log.d(TAG, "inject display=$displayId -> session=${target.session.windowDisplayId()} action=$action parent=${x.toInt()},${y.toInt()} local=$mappedX,$mappedY size=${iw}x$ih")
        val result = target.session.injectMouseEvent(action, pointerId, mappedX, mappedY, iw, ih, actionButton, buttons)
        if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
            synchronized(lock) { pointerTarget = null }
        }
        return result.getOrDefault(false)
    }

    suspend fun injectScroll(
        displayId: Int,
        x: Float,
        y: Float,
        hScroll: Float,
        vScroll: Float,
        buttons: Int,
        fallbackWidth: Int,
        fallbackHeight: Int,
    ): Boolean {
        val target = findAt(displayId, x, y) ?: return false
        val (mappedX, mappedY) = target.mapPoint(x, y)
        val (iw, ih) = target.session.inputCoordinateSize(target.width, target.height)
        Log.d(TAG, "scroll display=$displayId -> session=${target.session.windowDisplayId()} local=$mappedX,$mappedY")
        return target.session.injectScrollEvent(mappedX, mappedY, iw, ih, hScroll, vScroll, buttons).getOrDefault(false)
    }
}
