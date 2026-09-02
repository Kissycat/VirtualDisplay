package com.ynk.virtualdisplay.ui.display

import android.graphics.RectF
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.ynk.virtualdisplay.data.repository.IDisplayRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.os.Handler
import android.os.Looper

class InputController(
    private val repository: IDisplayRepository,
    private val displayIdProvider: () -> Int?,
    private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "InputController"
        private const val POINTER_ID_MOUSE = -1L
        private const val ACTION_POINTER_INDEX_SHIFT = 8
    }

    enum class DisplayMode {
        FIT_CENTER,
        CENTER_CROP,
        STRETCH_FILL,
    }

    @Volatile
    private var videoWidth: Int = 0

    @Volatile
    private var videoHeight: Int = 0

    @Volatile
    private var displayMode: DisplayMode = DisplayMode.FIT_CENTER

    @Volatile
    private var videoRotation: Int = 0

    private val activePointerIds = linkedSetOf<Int>()
    private val activePointerPositions = linkedMapOf<Int, Pair<Float, Float>>()

    // Desktop-mode trackpad state. The virtual display receives absolute mouse
    // coordinates, so the phone touchpad maintains an internal cursor position
    // and converts finger deltas into absolute mouse moves.
    private var trackpadCursorX = 0f
    private var trackpadCursorY = 0f
    private var trackpadLastX = 0f
    private var trackpadLastY = 0f
    private var trackpadDownX = 0f
    private var trackpadDownY = 0f
    private var trackpadDragArmX = 0f
    private var trackpadDragArmY = 0f
    private var trackpadDownTime = 0L
    private var trackpadMoved = false
    private var trackpadTapCancelled = false
    private var trackpadDragging = false
    private var trackpadModeEnabled = false
    private var trackpadLongPressStarted = false
    private var trackpadLongPressConsumed = false
    private var trackpadTwoFingerStartX = 0f
    private var trackpadTwoFingerStartY = 0f
    private var trackpadTwoFingerLastX = 0f
    private var trackpadTwoFingerLastY = 0f
    private var trackpadTwoFinger = false
    private var trackpadTwoFingerHorizontal = false
    private var trackpadTwoFingerSwipeStarted = false
    private var trackpadThreeFinger = false
    private var trackpadThreeFingerStartX = 0f
    private var trackpadThreeFingerStartY = 0f
    private var trackpadThreeFingerMoved = false
    private var trackpadThreeFingerClickTriggered = false
    private var trackpadMaxPointerCount = 0
    private var pendingTwoFingerClick = false
    private var pendingThreeFingerClick = false
    private val trackpadGestureHandler = Handler(Looper.getMainLooper())
    private val pendingTwoFingerClickRunnable = Runnable {
        if (pendingTwoFingerClick && trackpadMaxPointerCount == 2 &&
            trackpadTwoFinger && !trackpadMoved && !trackpadTwoFingerHorizontal &&
            displayIdProvider() != null) {
            injectMouseClick(displayIdProvider()!!, secondary = true)
        }
        pendingTwoFingerClick = false
        trackpadTwoFinger = false
        trackpadMaxPointerCount = 0
    }
    private val pendingThreeFingerClickRunnable = Runnable {
        if (pendingThreeFingerClick) {
            displayIdProvider()?.let { injectMouseClickButton(it, MotionEvent.BUTTON_TERTIARY) }
        }
        pendingThreeFingerClick = false
    }
    private val trackpadSensitivity = 0.72f
    // Movement before the long-press deadline cancels tap/drag qualification.
    private val trackpadMoveThreshold = 26f
    // After a stationary long-press, this smaller movement starts a drag.
    private val trackpadDragStartThreshold = 8f
    private val trackpadLongPressMs = 320L
    private val trackpadThreeFingerMoveThreshold = 48f

    /** Cancel a pending lower-priority multi-finger action when a higher
     * pointer count (4+) is observed by the Activity-level dispatcher. */
    fun cancelPendingMultiFingerClick() {
        trackpadGestureHandler.removeCallbacks(pendingTwoFingerClickRunnable)
        trackpadGestureHandler.removeCallbacks(pendingThreeFingerClickRunnable)
        pendingTwoFingerClick = false
        pendingThreeFingerClick = false
    }

    fun setTrackpadModeEnabled(enabled: Boolean) {
        if (trackpadModeEnabled == enabled) {
            // Even when the logical state is unchanged, explicitly clearing any
            // residual gesture state makes mode transitions deterministic.
            if (!enabled) {
                clearTrackpadGestureState()
            }
            return
        }

        if (!enabled) {
            val displayId = displayIdProvider()
            if (displayId != null && trackpadDragging) {
                injectMouseUp(displayId)
            }
            clearTrackpadGestureState()
        }
        trackpadModeEnabled = enabled
        Log.i(TAG, "trackpadModeEnabled=$enabled")
    }

    private fun clearTrackpadGestureState() {
        trackpadDragging = false
        trackpadLongPressStarted = false
        trackpadLongPressConsumed = false
        trackpadTwoFinger = false
        trackpadMoved = false
        trackpadTapCancelled = false
        trackpadGestureHandler.removeCallbacks(pendingTwoFingerClickRunnable)
        trackpadGestureHandler.removeCallbacks(pendingThreeFingerClickRunnable)
        pendingTwoFingerClick = false
        pendingThreeFingerClick = false
        trackpadMaxPointerCount = 0
        trackpadDownTime = 0L
        trackpadLastX = 0f
        trackpadLastY = 0f
        trackpadDownX = 0f
        trackpadDownY = 0f
        trackpadDragArmX = 0f
        trackpadDragArmY = 0f
        trackpadTwoFingerStartX = 0f
        trackpadTwoFingerStartY = 0f
        trackpadTwoFingerLastX = 0f
        trackpadTwoFingerLastY = 0f
        trackpadTwoFingerHorizontal = false
        trackpadTwoFingerSwipeStarted = false
        trackpadThreeFinger = false
        trackpadThreeFingerStartX = 0f
        trackpadThreeFingerStartY = 0f
        trackpadThreeFingerMoved = false
        trackpadThreeFingerClickTriggered = false
    }

    fun updateVideoSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
    }

    fun setDisplayMode(mode: DisplayMode) {
        displayMode = mode
    }

    fun setVideoRotation(rotation: Int) {
        videoRotation = rotation
    }

    fun hasVideoSize(): Boolean = videoWidth > 0 && videoHeight > 0

    fun shouldHandleRemotely(event: MotionEvent): Boolean {
        val source = event.source
        return source and InputDevice.SOURCE_CLASS_POINTER != 0 ||
            source and InputDevice.SOURCE_CLASS_POSITION != 0
    }

    fun handleMotionEvent(view: View, event: MotionEvent): Boolean {
        if (!hasVideoSize()) return false

        return when {
            isMouseEvent(event) -> handleMouseEvent(view, event)
            event.actionMasked == MotionEvent.ACTION_CANCEL -> handleCancel(view)
            else -> handleTouchEvent(view, event)
        }
    }

    /**
     * Desktop-mode touchpad. Single-finger movement controls an absolute mouse
     * cursor; tap = left click, drag = left-button drag, two-finger vertical
     * movement = wheel scroll, two-finger tap = right click.
     */
    fun handleTrackpadEvent(view: View, event: MotionEvent): Boolean {
        if (!trackpadModeEnabled || !hasVideoSize()) return true
        val displayId = displayIdProvider() ?: return false

        if (trackpadCursorX <= 0f && trackpadCursorY <= 0f) {
            trackpadCursorX = videoWidth * 0.5f
            trackpadCursorY = videoHeight * 0.5f
            DesktopCursorState.reset(videoWidth, videoHeight)
        }

        // Multi-touch arbitration is deliberately strict:
        // 1 finger = mouse
        // 2 fingers = candidate (never click immediately)
        // 3 fingers = middle click, committed immediately
        // 4+ fingers = reserved for higher-level desktop commands
        trackpadMaxPointerCount = maxOf(trackpadMaxPointerCount, event.pointerCount)

        if (event.pointerCount >= 4) {
            trackpadGestureHandler.removeCallbacks(pendingTwoFingerClickRunnable)
            pendingTwoFingerClick = false
            trackpadThreeFinger = false
            trackpadTwoFinger = false
            trackpadTapCancelled = true
            trackpadMoved = true
            if (trackpadDragging) {
                injectMouseUp(displayId)
                trackpadDragging = false
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                trackpadGestureHandler.removeCallbacks(pendingTwoFingerClickRunnable)
                pendingTwoFingerClick = false
                trackpadMaxPointerCount = 1
                trackpadDownTime = event.eventTime
                trackpadLastX = event.x
                trackpadLastY = event.y
                trackpadDownX = event.x
                trackpadDownY = event.y
                trackpadDragArmX = event.x
                trackpadDragArmY = event.y
                trackpadMoved = false
                trackpadTapCancelled = false
                trackpadDragging = false
                trackpadLongPressStarted = false
                trackpadLongPressConsumed = false
                trackpadTwoFinger = false
                trackpadTwoFingerStartX = 0f
                trackpadTwoFingerStartY = 0f
                trackpadTwoFingerLastX = 0f
                trackpadTwoFingerLastY = 0f
                trackpadTwoFingerHorizontal = false
                trackpadTwoFingerSwipeStarted = false
                trackpadThreeFinger = false
                trackpadThreeFingerMoved = false
                trackpadThreeFingerClickTriggered = false
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                when {
                    event.pointerCount >= 4 -> return true

                    event.pointerCount == 3 -> {
                        // Third finger has absolute priority. Commit middle-click
                        // now, not on release, so horizontal finger placement and
                        // release ordering cannot accidentally become a 2-finger tap.
                        trackpadGestureHandler.removeCallbacks(pendingTwoFingerClickRunnable)
                        pendingTwoFingerClick = false
                        trackpadThreeFinger = true
                        trackpadThreeFingerMoved = false
                        trackpadThreeFingerClickTriggered = false
                        trackpadTwoFinger = false
                        trackpadTwoFingerHorizontal = false
                        trackpadTwoFingerSwipeStarted = false
                        trackpadTapCancelled = true
                        trackpadMoved = true
                        if (trackpadDragging) {
                            injectMouseUp(displayId)
                            trackpadDragging = false
                        }
                        // Reserve 3-finger tap, but defer the actual middle-click
                        // briefly so a 4th pointer can still preempt it.
                        trackpadGestureHandler.removeCallbacks(pendingThreeFingerClickRunnable)
                        pendingThreeFingerClick = true
                        trackpadGestureHandler.postDelayed(pendingThreeFingerClickRunnable, 120L)
                        return true
                    }

                    event.pointerCount == 2 -> {
                        // Do not arm the right-click yet. A short grace window
                        // lets a 3rd/4th finger arrive before any click is emitted.
                        trackpadGestureHandler.removeCallbacks(pendingTwoFingerClickRunnable)
                        pendingTwoFingerClick = false
                        trackpadTwoFinger = true
                        trackpadTapCancelled = true
                        trackpadMoved = false
                        val x = (event.getX(0) + event.getX(1)) * 0.5f
                        val y = (event.getY(0) + event.getY(1)) * 0.5f
                        trackpadTwoFingerStartX = x
                        trackpadTwoFingerStartY = y
                        trackpadTwoFingerLastX = x
                        trackpadTwoFingerLastY = y
                        return true
                    }
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // If the framework delivers a MOVE with 3 pointers but skipped
                // POINTER_DOWN, promote it immediately and commit middle-click.
                if (event.pointerCount >= 3) {
                    if (!trackpadThreeFinger) {
                        trackpadGestureHandler.removeCallbacks(pendingTwoFingerClickRunnable)
                        pendingTwoFingerClick = false
                        trackpadThreeFinger = true
                        trackpadThreeFingerClickTriggered = false
                        trackpadTwoFinger = false
                        trackpadTwoFingerHorizontal = false
                        trackpadTapCancelled = true
                        trackpadMoved = true
                        if (trackpadDragging) {
                            injectMouseUp(displayId)
                            trackpadDragging = false
                        }
                        trackpadGestureHandler.removeCallbacks(pendingThreeFingerClickRunnable)
                        pendingThreeFingerClick = true
                        trackpadGestureHandler.postDelayed(pendingThreeFingerClickRunnable, 120L)
                    }
                    return true
                }

                if (event.pointerCount >= 2 || trackpadTwoFinger) {
                    if (event.pointerCount >= 2) {
                        val x = (event.getX(0) + event.getX(1)) * 0.5f
                        val y = (event.getY(0) + event.getY(1)) * 0.5f
                        val totalDx = x - trackpadTwoFingerStartX
                        val totalDy = y - trackpadTwoFingerStartY
                        if (!trackpadTwoFingerHorizontal &&
                            (kotlin.math.abs(totalDx) > trackpadMoveThreshold || kotlin.math.abs(totalDy) > trackpadMoveThreshold)) {
                            trackpadTwoFingerHorizontal = kotlin.math.abs(totalDx) > kotlin.math.abs(totalDy) * 1.2f
                        }

                        if (trackpadTwoFingerHorizontal) {
                            val dx = x - trackpadTwoFingerLastX
                            if (kotlin.math.abs(dx) > 0.5f) {
                                trackpadMoved = true
                                injectScrollEvent(trackpadCursorX, trackpadCursorY, dx / 288f, 0f, 0, displayId)
                            }
                        } else {
                            val dy = y - trackpadTwoFingerLastY
                            if (kotlin.math.abs(dy) > 0.5f) {
                                trackpadMoved = true
                                injectScrollEvent(trackpadCursorX, trackpadCursorY, 0f, dy / 288f, 0, displayId)
                            }
                        }
                        trackpadTwoFingerLastX = x
                        trackpadTwoFingerLastY = y
                    }
                    return true
                }

                val dx = event.x - trackpadLastX
                val dy = event.y - trackpadLastY
                trackpadLastX = event.x
                trackpadLastY = event.y

                val fromDownX = event.x - trackpadDownX
                val fromDownY = event.y - trackpadDownY
                val fromDownDistance = kotlin.math.hypot(fromDownX.toDouble(), fromDownY.toDouble()).toFloat()
                val elapsed = event.eventTime - trackpadDownTime

                if (!trackpadLongPressStarted && !trackpadTapCancelled &&
                    fromDownDistance > trackpadMoveThreshold) {
                    trackpadTapCancelled = true
                    trackpadMoved = true
                }

                if (!trackpadTwoFinger && !trackpadLongPressStarted &&
                    !trackpadTapCancelled && elapsed >= trackpadLongPressMs &&
                    fromDownDistance <= trackpadMoveThreshold) {
                    trackpadLongPressStarted = true
                    trackpadLongPressConsumed = true
                    trackpadDragArmX = event.x
                    trackpadDragArmY = event.y
                }

                if (dx != 0f || dy != 0f) {
                    trackpadCursorX = (trackpadCursorX + dx * trackpadSensitivity).coerceIn(0f, videoWidth.toFloat())
                    trackpadCursorY = (trackpadCursorY + dy * trackpadSensitivity).coerceIn(0f, videoHeight.toFloat())
                    DesktopCursorState.update(trackpadCursorX, trackpadCursorY, videoWidth, videoHeight)

                    if (!trackpadTwoFinger && trackpadLongPressStarted && !trackpadDragging) {
                        val armDx = event.x - trackpadDragArmX
                        val armDy = event.y - trackpadDragArmY
                        val armDistance = kotlin.math.hypot(armDx.toDouble(), armDy.toDouble()).toFloat()
                        if (armDistance >= trackpadDragStartThreshold) {
                            injectPointerEvent(MotionEvent.ACTION_DOWN, POINTER_ID_MOUSE,
                                trackpadCursorX, trackpadCursorY, videoWidth, videoHeight, 1f,
                                MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY, displayId,
                                InputDevice.SOURCE_MOUSE)
                            trackpadDragging = true
                        }
                    }

                    injectPointerEvent(
                        if (trackpadDragging) MotionEvent.ACTION_MOVE else MotionEvent.ACTION_HOVER_MOVE,
                        POINTER_ID_MOUSE, trackpadCursorX, trackpadCursorY, videoWidth, videoHeight, 1f,
                        0, if (trackpadDragging) MotionEvent.BUTTON_PRIMARY else 0, displayId,
                        InputDevice.SOURCE_MOUSE,
                    )
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (trackpadThreeFinger) return true
                if (trackpadTwoFinger && event.pointerCount <= 2) {
                    trackpadTwoFingerLastX = 0f
                    trackpadTwoFingerLastY = 0f
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Two-finger tap is the only gesture that is intentionally delayed.
                // Keep its state alive until the 90 ms arbitration window expires so
                // a late 3rd/4th pointer can still cancel it.
                if (trackpadDragging) {
                    injectMouseUp(displayId)
                } else if (trackpadThreeFinger) {
                    // Already committed when the 3rd pointer arrived.
                } else if (trackpadMaxPointerCount == 2 && trackpadTwoFinger &&
                    !trackpadTwoFingerHorizontal && !trackpadMoved &&
                    event.actionMasked == MotionEvent.ACTION_UP) {
                    pendingTwoFingerClick = true
                    trackpadGestureHandler.postDelayed(pendingTwoFingerClickRunnable, 90L)
                    return true
                } else if (trackpadMaxPointerCount == 1 &&
                    !trackpadTapCancelled && !trackpadLongPressConsumed && !trackpadMoved &&
                    event.actionMasked == MotionEvent.ACTION_UP) {
                    injectMouseClick(displayId, secondary = false)
                }

                trackpadTwoFinger = false
                trackpadDragging = false
                trackpadMoved = false
                trackpadTapCancelled = false
                trackpadLongPressStarted = false
                trackpadLongPressConsumed = false
                trackpadTwoFingerHorizontal = false
                trackpadTwoFingerSwipeStarted = false
                trackpadThreeFinger = false
                trackpadThreeFingerMoved = false
                trackpadThreeFingerClickTriggered = false
                trackpadMaxPointerCount = 0
                return true
            }
        }
        return true
    }

    private fun injectMouseClickButton(displayId: Int, button: Int) {
        injectPointerEvent(
            action = MotionEvent.ACTION_DOWN,
            pointerId = POINTER_ID_MOUSE,
            x = trackpadCursorX,
            y = trackpadCursorY,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            pressure = 1f,
            actionButton = button,
            buttons = button,
            displayId = displayId,
            source = InputDevice.SOURCE_MOUSE,
        )
        injectPointerEvent(
            action = MotionEvent.ACTION_UP,
            pointerId = POINTER_ID_MOUSE,
            x = trackpadCursorX,
            y = trackpadCursorY,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            pressure = 0f,
            actionButton = button,
            buttons = 0,
            displayId = displayId,
            source = InputDevice.SOURCE_MOUSE,
        )
    }

    private fun injectMouseClick(displayId: Int, secondary: Boolean) {
        val button = if (secondary) MotionEvent.BUTTON_SECONDARY else MotionEvent.BUTTON_PRIMARY
        injectPointerEvent(
            action = MotionEvent.ACTION_DOWN,
            pointerId = POINTER_ID_MOUSE,
            x = trackpadCursorX,
            y = trackpadCursorY,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            pressure = 1f,
            actionButton = button,
            buttons = button,
            displayId = displayId,
            source = InputDevice.SOURCE_MOUSE,
        )
        injectPointerEvent(
            action = MotionEvent.ACTION_UP,
            pointerId = POINTER_ID_MOUSE,
            x = trackpadCursorX,
            y = trackpadCursorY,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            pressure = 0f,
            actionButton = button,
            buttons = 0,
            displayId = displayId,
            source = InputDevice.SOURCE_MOUSE,
        )
    }

    private fun injectMouseUp(displayId: Int) {
        injectPointerEvent(
            action = MotionEvent.ACTION_UP,
            pointerId = POINTER_ID_MOUSE,
            x = trackpadCursorX,
            y = trackpadCursorY,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            pressure = 0f,
            actionButton = MotionEvent.BUTTON_PRIMARY,
            buttons = 0,
            displayId = displayId,
            source = InputDevice.SOURCE_MOUSE,
        )
    }

    /**
     * Finish and clear any touchscreen gesture which was already active before
     * desktop/WebRTC mode took control of the input surface. This prevents the
     * old touch stream from continuing to affect the remote application.
     */
    fun cancelActiveTouch() {
        val displayId = displayIdProvider() ?: return
        if (activePointerIds.isEmpty()) return

        val pointers = activePointerIds.toList()
        for (pointerId in pointers) {
            val pos = activePointerPositions[pointerId] ?: continue
            val x = if (videoWidth > 0) pos.first.coerceIn(0f, videoWidth.toFloat()) else pos.first
            val y = if (videoHeight > 0) pos.second.coerceIn(0f, videoHeight.toFloat()) else pos.second
            injectPointerEvent(
                action = MotionEvent.ACTION_UP,
                pointerId = pointerId.toLong(),
                x = x,
                y = y,
                videoWidth = videoWidth,
                videoHeight = videoHeight,
                pressure = 0f,
                actionButton = 0,
                buttons = 0,
                displayId = displayId,
                source = InputDevice.SOURCE_TOUCHSCREEN,
            )
        }
        activePointerIds.clear()
        activePointerPositions.clear()
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) {
            return false
        }
        val displayId = displayIdProvider() ?: return false
        scope.launch(Dispatchers.Main.immediate) {
            repository.injectInputWithDisplayId(event, displayId)
        }
        return true
    }

    fun injectKey(keyCode: Int) {
        val displayId = displayIdProvider() ?: return
        val now = android.os.SystemClock.uptimeMillis()
        val downEvent = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        val upEvent = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
        scope.launch(Dispatchers.Main.immediate) {
            repository.injectInputWithDisplayId(downEvent, displayId)
            repository.injectInputWithDisplayId(upEvent, displayId)
        }
    }

    private fun isMouseEvent(event: MotionEvent): Boolean {
        return event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.actionMasked == MotionEvent.ACTION_HOVER_ENTER ||
            event.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
            event.actionMasked == MotionEvent.ACTION_HOVER_EXIT ||
            event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
    }

    private fun handleTouchEvent(view: View, event: MotionEvent): Boolean {
        val displayId = displayIdProvider() ?: return false
        val contentRect = getContentRect(view) ?: return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val actionIndex = event.actionIndex
                val pointerId = event.getPointerId(actionIndex)
                val rawX = event.getX(actionIndex)
                val rawY = event.getY(actionIndex)
                if (!isInsideContent(rawX, rawY, contentRect)) return false
                val targetPos = mapToVideo(rawX, rawY, contentRect)
                activePointerIds += pointerId
                activePointerPositions[pointerId] = rawX to rawY
                injectTouchPointer(event, actionIndex, MotionEvent.ACTION_DOWN, pointerId, targetPos, displayId)
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pointerId = event.getPointerId(i)
                    if (pointerId !in activePointerIds) continue
                    val rawX = event.getX(i)
                    val rawY = event.getY(i)
                    activePointerPositions[pointerId] = rawX to rawY
                    val targetPos = mapToVideo(rawX, rawY, contentRect)
                    injectTouchPointer(event, i, MotionEvent.ACTION_MOVE, pointerId, targetPos, displayId)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val actionIndex = event.actionIndex
                val pointerId = event.getPointerId(actionIndex)
                val rawX = event.getX(actionIndex)
                val rawY = event.getY(actionIndex)
                val targetPos = mapToVideo(rawX, rawY, contentRect)
                injectTouchPointer(event, actionIndex, MotionEvent.ACTION_UP, pointerId, targetPos, displayId)
                activePointerIds -= pointerId
                activePointerPositions.remove(pointerId)
            }
        }

        handleDisappearedPointers(event, contentRect, displayId)
        return true
    }

    private fun handleMouseEvent(view: View, event: MotionEvent): Boolean {
        val displayId = displayIdProvider() ?: return false
        val contentRect = getContentRect(view) ?: return false

        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE,
            MotionEvent.ACTION_HOVER_EXIT,
            -> {
                val (x, y) = mapToVideo(event.getX(0), event.getY(0), contentRect)
                injectPointerEvent(
                    action = event.actionMasked,
                    pointerId = POINTER_ID_MOUSE,
                    x = x,
                    y = y,
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                    pressure = event.getPressure(0).coerceIn(0f, 1f),
                    actionButton = event.actionButton,
                    buttons = event.buttonState,
                    displayId = displayId,
                    source = InputDevice.SOURCE_MOUSE,
                )
            }

            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> {
                val (x, y) = mapToVideo(event.getX(0), event.getY(0), contentRect)
                injectPointerEvent(
                    action = MotionEvent.ACTION_DOWN,
                    pointerId = POINTER_ID_MOUSE,
                    x = x,
                    y = y,
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                    pressure = event.getPressure(0).coerceIn(0f, 1f),
                    actionButton = event.actionButton,
                    buttons = event.buttonState,
                    displayId = displayId,
                    source = InputDevice.SOURCE_MOUSE,
                )
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> {
                val (x, y) = mapToVideo(event.getX(0), event.getY(0), contentRect)
                injectPointerEvent(
                    action = MotionEvent.ACTION_UP,
                    pointerId = POINTER_ID_MOUSE,
                    x = x,
                    y = y,
                    videoWidth = videoWidth,
                    videoHeight = videoHeight,
                    pressure = 0f,
                    actionButton = event.actionButton,
                    buttons = 0,
                    displayId = displayId,
                    source = InputDevice.SOURCE_MOUSE,
                )
            }

            MotionEvent.ACTION_SCROLL -> {
                val (x, y) = mapToVideo(event.getX(0), event.getY(0), contentRect)
                injectScrollEvent(
                    x = x,
                    y = y,
                    hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                    vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL),
                    buttons = event.buttonState,
                    displayId = displayId,
                )
            }
        }

        return true
    }

    private fun handleCancel(view: View): Boolean {
        val displayId = displayIdProvider() ?: return false
        val contentRect = getContentRect(view) ?: return false
        val toCancel = activePointerIds.toList()
        for (pointerId in toCancel) {
            val pos = activePointerPositions[pointerId] ?: continue
            val (x, y) = mapToVideo(pos.first, pos.second, contentRect)
            injectPointerEvent(
                action = MotionEvent.ACTION_UP,
                pointerId = pointerId.toLong(),
                x = x,
                y = y,
                videoWidth = videoWidth,
                videoHeight = videoHeight,
                pressure = 0f,
                actionButton = 0,
                buttons = 0,
                displayId = displayId,
                source = InputDevice.SOURCE_TOUCHSCREEN,
            )
        }
        activePointerIds.clear()
        activePointerPositions.clear()
        return true
    }

    private fun handleDisappearedPointers(event: MotionEvent, contentRect: RectF, displayId: Int) {
        val currentPointerIds = (0 until event.pointerCount).map { event.getPointerId(it) }.toSet()
        val disappeared = activePointerIds.filter { it !in currentPointerIds }
        for (pointerId in disappeared) {
            val pos = activePointerPositions[pointerId] ?: continue
            val (x, y) = mapToVideo(pos.first, pos.second, contentRect)
            injectPointerEvent(
                action = MotionEvent.ACTION_UP,
                pointerId = pointerId.toLong(),
                x = x,
                y = y,
                videoWidth = videoWidth,
                videoHeight = videoHeight,
                pressure = 0f,
                actionButton = 0,
                buttons = 0,
                displayId = displayId,
                source = InputDevice.SOURCE_TOUCHSCREEN,
            )
            activePointerIds -= pointerId
            activePointerPositions.remove(pointerId)
        }
    }

    private fun injectTouchPointer(
        event: MotionEvent,
        pointerIndex: Int,
        action: Int,
        pointerId: Int,
        targetPos: Pair<Float, Float>,
        displayId: Int,
    ) {
        val (x, y) = targetPos
        val pointerCount = event.pointerCount
        val pointerProperties = Array(pointerCount) { MotionEvent.PointerProperties() }
        val pointerCoords = Array(pointerCount) { MotionEvent.PointerCoords() }

        for (index in 0 until pointerCount) {
            event.getPointerProperties(index, pointerProperties[index])
            event.getPointerCoords(index, pointerCoords[index])
            if (index == pointerIndex || pointerProperties[index].id == pointerId) {
                pointerCoords[index].x = x
                pointerCoords[index].y = y
            } else {
                val rawX = event.getX(index)
                val rawY = event.getY(index)
                val contentRect = getContentRectForInjection() ?: continue
                val mapped = mapToVideo(rawX, rawY, contentRect)
                pointerCoords[index].x = mapped.first
                pointerCoords[index].y = mapped.second
            }
        }

        val newAction = if (pointerCount == 1) {
            action
        } else {
            when (action) {
                MotionEvent.ACTION_DOWN -> MotionEvent.ACTION_POINTER_DOWN
                MotionEvent.ACTION_UP -> MotionEvent.ACTION_POINTER_UP
                else -> action
            }
        }

        val pointerIndexAdjusted = if (pointerCount > 1) {
            pointerProperties.indexOfFirst { it.id == pointerId }.coerceAtLeast(0)
        } else 0

        val finalAction = newAction or (pointerIndexAdjusted shl ACTION_POINTER_INDEX_SHIFT)

        val newEvent = MotionEvent.obtain(
            event.downTime,
            event.eventTime,
            finalAction,
            pointerCount,
            pointerProperties,
            pointerCoords,
            event.metaState,
            event.buttonState,
            0f,
            0f,
            0,
            event.edgeFlags,
            InputDevice.SOURCE_TOUCHSCREEN,
            event.flags,
        )

        injectEvent(newEvent, displayId)
    }

    private var cachedContentRect: RectF? = null

    private fun getContentRectForInjection(): RectF? = cachedContentRect

    private fun injectPointerEvent(
        action: Int,
        pointerId: Long,
        x: Float,
        y: Float,
        videoWidth: Int,
        videoHeight: Int,
        pressure: Float,
        actionButton: Int,
        buttons: Int,
        displayId: Int,
        source: Int,
    ) {
        val pointerProperties = arrayOf(MotionEvent.PointerProperties().apply {
            id = pointerId.toInt()
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val pointerCoords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            this.pressure = pressure
            this.size = 1f
        })

        val event = MotionEvent.obtain(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis(),
            action,
            1,
            pointerProperties,
            pointerCoords,
            0,
            buttons,
            0f,
            0f,
            0,
            0,
            source,
            0,
        )
        injectEvent(event, displayId)
    }

    private fun injectScrollEvent(
        x: Float,
        y: Float,
        hScroll: Float,
        vScroll: Float,
        buttons: Int,
        displayId: Int,
    ) {
        val pointerProperties = arrayOf(MotionEvent.PointerProperties().apply {
            id = POINTER_ID_MOUSE.toInt()
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val pointerCoords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
            setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
        })

        val event = MotionEvent.obtain(
            SystemClock.uptimeMillis(),
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_SCROLL,
            1,
            pointerProperties,
            pointerCoords,
            0,
            buttons,
            0f,
            0f,
            0,
            0,
            InputDevice.SOURCE_MOUSE,
            0,
        )
        injectEvent(event, displayId)
    }

    private fun injectEvent(event: MotionEvent, displayId: Int) {
        scope.launch(Dispatchers.Main.immediate) {
            try {
                val result = repository.injectInputWithDisplayId(event, displayId)
                if (result.isFailure || result.getOrDefault(false).not()) {
                    Log.e(TAG, "injectMotionEvent failed", result.exceptionOrNull())
                }
            } finally {
                event.recycle()
            }
        }
    }

    fun getContentRect(view: View): RectF? {
        val width = view.width.toFloat()
        val height = view.height.toFloat()
        val vw = videoWidth.toFloat()
        val vh = videoHeight.toFloat()
        if (width <= 0f || height <= 0f || vw <= 0f || vh <= 0f) {
            cachedContentRect = null
            return null
        }

        val rect = when (displayMode) {
            DisplayMode.FIT_CENTER -> {
                val videoAspect = vw / vh
                val viewAspect = width / height
                if (videoAspect > viewAspect) {
                    val contentHeight = width / videoAspect
                    val top = (height - contentHeight) * 0.5f
                    RectF(0f, top, width, top + contentHeight)
                } else {
                    val contentWidth = height * videoAspect
                    val left = (width - contentWidth) * 0.5f
                    RectF(left, 0f, left + contentWidth, height)
                }
            }

            DisplayMode.CENTER_CROP -> {
                val videoAspect = vw / vh
                val viewAspect = width / height
                if (videoAspect > viewAspect) {
                    val visibleFraction = viewAspect / videoAspect
                    val cropOffset = (1f - visibleFraction) * 0.5f
                    val left = -width * cropOffset / visibleFraction
                    val right = width + width * cropOffset / visibleFraction
                    RectF(left, 0f, right, height)
                } else {
                    val visibleFraction = videoAspect / viewAspect
                    val cropOffset = (1f - visibleFraction) * 0.5f
                    val top = -height * cropOffset / visibleFraction
                    val bottom = height + height * cropOffset / visibleFraction
                    RectF(0f, top, width, bottom)
                }
            }

            DisplayMode.STRETCH_FILL -> {
                RectF(0f, 0f, width, height)
            }
        }

        cachedContentRect = rect
        return rect
    }

    private fun isInsideContent(x: Float, y: Float, rect: RectF): Boolean {
        return x in rect.left..rect.right && y in rect.top..rect.bottom
    }

    private fun mapToVideo(x: Float, y: Float, rect: RectF): Pair<Float, Float> {
        val normalizedX = ((x - rect.left) / rect.width()).coerceIn(0f, 1f)
        val normalizedY = ((y - rect.top) / rect.height()).coerceIn(0f, 1f)

        val mappedX: Float
        val mappedY: Float
        when (videoRotation) {
            90 -> {
                mappedX = normalizedY * videoWidth
                mappedY = (1f - normalizedX) * videoHeight
            }
            180 -> {
                mappedX = (1f - normalizedX) * videoWidth
                mappedY = (1f - normalizedY) * videoHeight
            }
            270 -> {
                mappedX = (1f - normalizedY) * videoWidth
                mappedY = normalizedX * videoHeight
            }
            else -> {
                mappedX = normalizedX * videoWidth
                mappedY = normalizedY * videoHeight
            }
        }

        return mappedX to mappedY
    }

    private fun Int.saturatingSubtract(value: Int): Int = (this - value).coerceAtLeast(0)

    private object SystemClock {
        fun uptimeMillis(): Long = android.os.SystemClock.uptimeMillis()
    }
}
