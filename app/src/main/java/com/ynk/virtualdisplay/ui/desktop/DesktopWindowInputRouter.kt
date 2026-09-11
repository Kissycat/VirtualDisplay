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
        val promoteVisual: () -> Unit,
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
    private var focusedTarget: WeakReference<DesktopWindowSession>? = null

    fun register(
        session: DesktopWindowSession,
        displayId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        chromeTop: Int,
        promoteVisual: () -> Unit = {},
    ) {
        synchronized(lock) {
            entries[session] = Entry(session, displayId, x, y, width, height, chromeTop, promoteVisual)
            focusedTarget = WeakReference(session)
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
            entries[session]?.let { current ->
                entries[session] = Entry(
                    session, displayId, x, y, width, height, chromeTop, current.promoteVisual
                )
            }
        }
    }

    fun bringToFront(session: DesktopWindowSession) {
        val promote: (() -> Unit)?
        synchronized(lock) {
            val e = entries.remove(session) ?: return
            entries[session] = e
            pointerTarget = WeakReference(session)
            focusedTarget = WeakReference(session)
            promote = e.promoteVisual
        }
        promote?.invoke()
        Log.d(TAG, "front sessionDisplay=${session.windowDisplayId()}")
    }

    fun clearFocus() {
        synchronized(lock) {
            focusedTarget = null
            pointerTarget = null
        }
        Log.d(TAG, "focus cleared")
    }

    fun focusedSession(): DesktopWindowSession? = synchronized(lock) {
        focusedTarget?.get()?.takeIf { entries.containsKey(it) }
    }

    fun focusSession(session: DesktopWindowSession) {
        if (synchronized(lock) { !entries.containsKey(session) }) return
        bringToFront(session)
    }

    fun unregister(session: DesktopWindowSession) {
        synchronized(lock) {
            entries.remove(session)
            if (pointerTarget?.get() === session) pointerTarget = null
            if (focusedTarget?.get() === session) focusedTarget = null
        }
        Log.d(TAG, "unregister sessionDisplay=${session.windowDisplayId()}")
    }

    private fun findAt(displayId: Int, x: Float, y: Float): Entry? = synchronized(lock) {
        entries.values.asSequence().filter { it.displayId == displayId && it.containsContent(x, y) }.lastOrNull()
    }

    suspend fun injectKeyEvent(event: android.view.KeyEvent): Boolean {
        val session = focusedSession() ?: return false
        return session.injectKeyEvent(event).getOrDefault(false)
    }

    suspend fun injectKeyCode(keyCode: Int): Boolean {
        val session = focusedSession() ?: return false
        return session.injectKeyCode(keyCode).getOrDefault(false)
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
        if (target == null) {
            if (action == android.view.MotionEvent.ACTION_DOWN) clearFocus()
            return false
        }

        if (action == android.view.MotionEvent.ACTION_DOWN) {
            synchronized(lock) {
                pointerTarget = WeakReference(target.session)
                focusedTarget = WeakReference(target.session)
            }
            // A click on an exposed part of a window promotes it so subsequent
            // overlap hit-tests use the same visual stacking order.
            target.promoteVisual.invoke()
            synchronized(lock) {
                val e = entries.remove(target.session)
                if (e != null) entries[target.session] = e
            }
        }

        val (mappedX, mappedY) = target.mapPoint(x, y)
        val (iw, ih) = target.session.inputCoordinateSize(target.width, target.height)
        if (action != android.view.MotionEvent.ACTION_HOVER_MOVE &&
            action != android.view.MotionEvent.ACTION_HOVER_ENTER) {
            Log.d(TAG, "inject display=$displayId -> session=${target.session.windowDisplayId()} action=$action parent=${x.toInt()},${y.toInt()} local=$mappedX,$mappedY size=${iw}x$ih")
        }
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
        if (hScroll != 0f || vScroll != 0f) {
            Log.d(TAG, "scroll display=$displayId -> session=${target.session.windowDisplayId()} local=$mappedX,$mappedY")
        }
        return target.session.injectScrollEvent(mappedX, mappedY, iw, ih, hScroll, vScroll, buttons).getOrDefault(false)
    }
}
