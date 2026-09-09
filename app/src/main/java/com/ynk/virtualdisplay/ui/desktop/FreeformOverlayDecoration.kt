/*
 * LMO-style decoration projected onto the EXISTING main virtual display.
 *
 * LMO-style chrome hosted on the parent DesktopShell display.
 *
 * Because the system lmo_freeform service is SYSTEM_UID-only, this app-side
 * implementation uses one private VirtualDisplay + one independent scrcpy
 * session for each window's app content. The chrome itself is always created
 * from the parent DesktopShell display context, so it stays inside the
 * corresponding desktop mode rather than appearing on the physical display.
 */
package com.ynk.virtualdisplay.ui.desktop

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Display
import android.view.TextureView
import android.view.Gravity
import android.view.MotionEvent
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import android.util.Log

internal class FreeformOverlayDecoration(
    private val activity: DesktopShellActivity,
    val packageName: String,
    private val initialX: Int = activity.dpPublic(120),
    private val initialY: Int = activity.dpPublic(80),
    private val initialContentWidth: Int = activity.dpPublic(900),
    private val initialContentHeight: Int = activity.dpPublic(600),
    private val portraitMode: Boolean = false,
    private val splitMode: Boolean = false,
) {
    companion object {
        private const val INPUT_TAG = "DesktopWindowInput"
        private const val HEADER_DP = 40
        private const val FOOTER_DP = 32
        private const val DEFAULT_W_DP = 900
        private const val DEFAULT_H_DP = 600
        private const val MIN_W_DP = 280
        private const val MIN_H_DP = 220
        private const val HANGUP_W_DP = 300
        private const val HANGUP_H_DP = 400
    }

    private val desktopDisplay: Display = activity.display
        ?: throw IllegalStateException("Desktop display unavailable")

    // The whole decoration is a real child of DesktopShellActivity's
    // in-display overlay layer. Keeping a single FrameLayout root makes all
    // chrome and the content TextureView participate in the same View tree.
    private val root = FrameLayout(activity).apply {
        clipChildren = false
        clipToPadding = false
        isClickable = false
    }

    private val header = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(activity.dpPublic(8), 0, activity.dpPublic(4), 0)
        background = activity.roundedPublic(0xF01B1F26.toInt(), 10f)
    }
    private val icon = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setImageDrawable(runCatching { activity.packageManager.getApplicationIcon(packageName) }
            .getOrElse { activity.getDrawable(android.R.drawable.sym_def_app_icon) })
    }
    private val title = TextView(activity).apply {
        setTextColor(Color.WHITE)
        textSize = 11f
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val pin = TextView(activity).apply {
        setTextColor(0xFFF2F5FA.toInt())
        text = "—"
        gravity = Gravity.CENTER
        textSize = 18f
        isClickable = true
        contentDescription = "最小化"
    }
    private val maximizeButton = TextView(activity).apply {
        setTextColor(0xFFF2F5FA.toInt())
        text = "□"
        gravity = Gravity.CENTER
        textSize = 18f
        isClickable = true
        contentDescription = "全屏"
    }
    private val close = TextView(activity).apply {
        setTextColor(0xFFF2F5FA.toInt())
        text = "×"
        gravity = Gravity.CENTER
        textSize = 20f
        isClickable = true
        contentDescription = "关闭"
    }
    private val bottom = FrameLayout(activity).apply {
        setBackgroundColor(0xE51B1F26.toInt())
    }
    private val pill = View(activity).apply {
        background = GradientDrawable().apply {
            setColor(0xD9FFFFFF.toInt())
            cornerRadius = activity.dpPublic(4).toFloat()
        }
    }
    private val leftScale = TriangleHandleView(activity, true)
    private val rightScale = TriangleHandleView(activity, false)
    private val exitTrigger = ExitTriggerView(activity) {
        Log.i("FreeformOverlayDecoration", "EXIT_TRIGGER_VISIBLE pkg=$packageName display=${windowSession.windowDisplayId()}")
        closeWindowFromSessionEnd()
    }.apply {
        visibility = View.GONE
        isClickable = false
        isFocusable = false
    }
    private val veil = FrameLayout(activity).apply {
        setBackgroundColor(0x66000000)
        visibility = View.GONE
    }

    private val contentBackground = View(activity).apply {
        setBackgroundColor(Color.BLACK)
    }
    private val content = TextureView(activity).apply {
        isOpaque = false
    }

    private var attached = false
    private var released = false
    private var closing = false
    private var overlayAttached = false
    private var overlayWm: android.view.WindowManager? = null
    private var overlayParams: android.view.WindowManager.LayoutParams? = null
    private var hanging = false
    private var minimized = false
    private var mouseButtonDown = false

    private val windowSession = DesktopWindowSession(desktopDisplay.displayId, packageName)
    private val sessionScope = MainScope()
    private var sessionStarted = false

    init {
        windowSession.onSessionEnded = { reason ->
            Log.i("FreeformOverlayDecoration", "SESSION_ENDED pkg=$packageName display=${windowSession.windowDisplayId()} reason=$reason -> trigger")
            activity.runOnUiThread { if (!released) exitTrigger.visibility = View.VISIBLE }
        }
    }
    private var resizeJob: Job? = null

    private var contentWidth = initialContentWidth
    private var contentHeight = initialContentHeight
    private var lastWidth = contentWidth
    private var lastHeight = contentHeight

    private var windowX = initialX
    private var windowY = initialY

    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragBaseX = 0
    private var dragBaseY = 0
    private var resizeStartRawX = 0f
    private var resizeStartRawY = 0f
    private var resizeBaseW = 0
    private var resizeBaseH = 0
    private var resizeBaseX = 0
    private var resizeRight = true
    private var gestureDownY = 0f
    private var gestureDownX = 0f
				private var lastTapUpTime = 0L
    private var lastTapUpX = 0f
    private var lastTapUpY = 0f
    private var doubleTapDragArmed = false

    private val topChrome = activity.dpPublic(HEADER_DP)
    private val bottomChrome = activity.dpPublic(FOOTER_DP)

    val displayLabel: String
        get() = runCatching {
            activity.packageManager
                .getApplicationLabel(activity.packageManager.getApplicationInfo(packageName, 0))
                .toString()
        }.getOrElse { packageName.substringAfterLast('.') }

    init {
        title.text = displayLabel
        header.addView(icon, LinearLayout.LayoutParams(activity.dpPublic(30), topChrome))
        header.addView(title, LinearLayout.LayoutParams(0, topChrome, 1f))
        header.addView(pin, LinearLayout.LayoutParams(activity.dpPublic(38), topChrome))
        header.addView(maximizeButton, LinearLayout.LayoutParams(activity.dpPublic(38), topChrome))
        header.addView(close, LinearLayout.LayoutParams(activity.dpPublic(38), topChrome))

        root.addView(contentBackground, FrameLayout.LayoutParams(contentWidth, contentHeight).apply {
            topMargin = topChrome
        })
        root.addView(content, FrameLayout.LayoutParams(contentWidth, contentHeight).apply {
            topMargin = topChrome
        })
        root.addView(veil, FrameLayout.LayoutParams(contentWidth, contentHeight).apply {
            topMargin = topChrome
        })
        root.addView(exitTrigger, FrameLayout.LayoutParams(contentWidth, activity.dpPublic(3)).apply {
            gravity = Gravity.BOTTOM
        })
        root.addView(header, FrameLayout.LayoutParams(contentWidth, topChrome, Gravity.TOP))
        root.addView(bottom, FrameLayout.LayoutParams(contentWidth, bottomChrome, Gravity.TOP).apply {
            topMargin = topChrome + contentHeight
        })
        bottom.addView(pill, FrameLayout.LayoutParams(activity.dpPublic(112), activity.dpPublic(6), Gravity.CENTER))
        bottom.addView(leftScale, FrameLayout.LayoutParams(activity.dpPublic(22), bottomChrome, Gravity.START))
        bottom.addView(rightScale, FrameLayout.LayoutParams(activity.dpPublic(22), bottomChrome, Gravity.END))

        header.setOnTouchListener { _, event -> handleMove(event) }
        bottom.setOnTouchListener { _, event -> handlePill(event) }
        leftScale.setOnTouchListener { _, event -> handleScale(event, false) }
        rightScale.setOnTouchListener { _, event -> handleScale(event, true) }
        content.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                surface.setDefaultBufferSize(contentWidth, contentHeight)
                val s = android.view.Surface(surface)
                sessionScope.launch(Dispatchers.IO) {
                    val result = windowSession.start(s, contentWidth, contentHeight, activity.desktopDpiPublic())
                    sessionStarted = result.isSuccess
                    if (result.isFailure) s.release()
                    if (result.isFailure) {
                        android.util.Log.e("FreeformOverlayDecoration", "start desktop window session failed", result.exceptionOrNull())
                    }
                }
            }
            override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) = Unit
        }
        content.isClickable = true
        content.isFocusable = true
        content.isFocusableInTouchMode = true
        content.setOnFocusChangeListener { _, hasFocus ->
            Log.d(INPUT_TAG, "FOCUS pkg=$packageName hasFocus=$hasFocus display=${desktopDisplay.displayId} sessionDisplay=${windowSession.windowDisplayId()}")
            if (hasFocus) {
                bringToFront()
            }
        }
        content.setOnTouchListener { view, event ->
            if (released || minimized || hanging) return@setOnTouchListener false
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_DOWN) {
                view.requestFocusFromTouch()
                bringToFront()
            }
            if (action != MotionEvent.ACTION_MOVE || event.eventTime - event.downTime < 80L) {
                Log.d(INPUT_TAG, "TOUCH_UI pkg=$packageName action=$action pointers=${event.pointerCount} x=${event.x} y=${event.y} focus=${view.hasFocus()} display=${desktopDisplay.displayId} sessionDisplay=${windowSession.windowDisplayId()} size=${contentWidth}x${contentHeight}")
            }

            // Follow the normal-mode touch path: keep the original multi-pointer
            // MotionEvent semantics and inject it through this window's private
            // ROLE_CONTROL session as a touchscreen event. Do not route it via
            // the desktop trackpad cursor, which belongs exclusively to the main
            // virtual display.
            val copy = prepareTouchEventForVideo(event)
            copy.setSource(InputDevice.SOURCE_TOUCHSCREEN)
            Log.d(INPUT_TAG, "TOUCH_QUEUE pkg=$packageName action=${copy.actionMasked} source=${copy.source} downTime=${copy.downTime} eventTime=${copy.eventTime}")
            sessionScope.launch(Dispatchers.IO) {
                try {
                    val (inputWidth, inputHeight) = windowSession.inputCoordinateSize(contentWidth, contentHeight)
                    windowSession.injectInput(copy, inputWidth, inputHeight)
                        .onFailure { activity.logDesktopWindow("touch input failed", it) }
                } finally {
                    copy.recycle()
                }
            }
            true
        }
        content.setOnGenericMotionListener { _, event ->
            if (released || minimized || hanging) return@setOnGenericMotionListener false
            val source = event.source
            val action = event.actionMasked
            val isMouse = (source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
                (event.pointerCount > 0 && event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE)
            if (!isMouse) return@setOnGenericMotionListener false
            when (action) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE,
                MotionEvent.ACTION_HOVER_EXIT, MotionEvent.ACTION_SCROLL,
                MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                    val copy = MotionEvent.obtain(event)
                    val index = copy.actionIndex.coerceIn(0, (copy.pointerCount - 1).coerceAtLeast(0))
                    val rawPx = if (copy.pointerCount > 0) copy.getX(index).toInt() else 0
                    val rawPy = if (copy.pointerCount > 0) copy.getY(index).toInt() else 0
                    val (px, py) = windowSession.mapContentPointToVideo(rawPx, rawPy, contentWidth, contentHeight)
                    val pointerId = if (copy.pointerCount > 0) copy.getPointerId(index).toLong() else -1L
                    val actionButton = copy.actionButton
                    val buttons = copy.buttonState
                    Log.d(INPUT_TAG, "MOUSE_UI pkg=$packageName action=${copy.actionMasked} x=$px y=$py actionButton=$actionButton buttons=$buttons source=${copy.source} sessionDisplay=${windowSession.windowDisplayId()}")
                    sessionScope.launch(Dispatchers.IO) {
                        runCatching {
                            windowSession.injectMouseEvent(
                                action = copy.actionMasked,
                                pointerId = pointerId,
                                x = px,
                                y = py,
                                width = windowSession.inputCoordinateSize(contentWidth, contentHeight).first,
                                height = windowSession.inputCoordinateSize(contentWidth, contentHeight).second,
                                actionButton = actionButton,
                                buttons = buttons,
                            )
                        }.onFailure { activity.logDesktopWindow("generic mouse input failed", it) }
                        copy.recycle()
                    }
                    true
                }
                else -> false
            }
        }
        pin.setOnClickListener { toggleMinimized() }
        maximizeButton.setOnClickListener { maximize() }
        close.setOnClickListener { closeWindow() }
    }

    private fun attachAsDisplayOverlay() {
        if (overlayAttached || root.parent != null) return
        if (!activity.ensureOverlayPermissionSilentlyPublic()) {
            throw IllegalStateException("SYSTEM_ALERT_WINDOW unavailable")
        }
        val ctx = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            activity.createDisplayContext(desktopDisplay).createWindowContext(
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null
            )
        } else {
            activity.createDisplayContext(desktopDisplay)
        }
        val wm = ctx.getSystemService(android.view.WindowManager::class.java)
            ?: throw IllegalStateException("WindowManager unavailable for display ${desktopDisplay.displayId}")
        val chromeHeight = if (hanging) 0 else topChrome + bottomChrome
        val params = android.view.WindowManager.LayoutParams(
            contentWidth,
            contentHeight + chromeHeight,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = windowX
            y = windowY
            title = "DesktopFreeform-$packageName-${desktopDisplay.displayId}"
        }
        wm.addView(root, params)
        overlayWm = wm
        overlayParams = params
        overlayAttached = true
        Log.i("FreeformOverlayDecoration", "OVERLAY_ATTACHED pkg=$packageName display=${desktopDisplay.displayId} x=$windowX y=$windowY size=${params.width}x${params.height}")
    }

    fun show() {
        if (attached || released) return
        exitTrigger.visibility = View.GONE
        if (activity.isFinishing || activity.isDestroyed) {
            releaseSilently()
            return
        }
        applyRootSize()
        attached = runCatching {
            attachAsDisplayOverlay()
            DesktopWindowInputRouter.register(windowSession, desktopDisplay.displayId, windowX, windowY, contentWidth, contentHeight, if (hanging) 0 else topChrome)
            true
        }.onFailure {
            activity.logDesktopWindow("Failed to attach desktop window overlay, fallback to shell layer", it)
            runCatching {
                activity.addDesktopWindowView(root,
                    contentWidth,
                    contentHeight + if (hanging) 0 else topChrome + bottomChrome,
                    windowX,
                    windowY)
            }.isSuccess
        }.getOrDefault(false)
        if (!attached) {
            releaseSilently()
        } else {
        }
    }

    fun isVisible(): Boolean = attached && !released && root.visibility == View.VISIBLE

    fun bringToFront() {
        if (!attached || released) return
        if (minimized) toggleMinimized()
        if (overlayAttached) {
            overlayParams?.let {
                it.x = windowX
                it.y = windowY
                runCatching { overlayWm?.updateViewLayout(root, it) }
            }
            Log.d("FreeformOverlayDecoration", "OVERLAY_FRONT pkg=$packageName display=${desktopDisplay.displayId}")
        } else {
            activity.bringDesktopWindowToFront(root)
        }
        DesktopWindowInputRouter.bringToFront(windowSession)
    }

    private fun prepareTouchEventForVideo(event: MotionEvent): MotionEvent {
        val (vw, vh) = windowSession.inputCoordinateSize(contentWidth, contentHeight)
        if (vw == contentWidth && vh == contentHeight) return MotionEvent.obtain(event)
        val props = Array(event.pointerCount) { index ->
            MotionEvent.PointerProperties().also { event.getPointerProperties(index, it) }
        }
        val coords = Array(event.pointerCount) { index ->
            MotionEvent.PointerCoords().also {
                event.getPointerCoords(index, it)
                it.x = it.x * vw.toFloat() / contentWidth.coerceAtLeast(1)
                it.y = it.y * vh.toFloat() / contentHeight.coerceAtLeast(1)
            }
        }
        return MotionEvent.obtain(
            event.downTime, event.eventTime, event.action, event.pointerCount,
            props, coords, event.metaState, event.buttonState, event.xPrecision,
            event.yPrecision, event.deviceId, event.edgeFlags, event.source, event.flags
        )
    }

    private fun handleMove(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragBaseX = windowX
                dragBaseY = windowY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                windowX = dragBaseX + (event.rawX - dragStartRawX).roundToInt()
                windowY = dragBaseY + (event.rawY - dragStartRawY).roundToInt()
                // Move the actual top-level overlay on every pointer sample;
                // using the shell-layer helper here only updated the final
                // position after the drag ended.
                updateAttachedLayout()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                clampInScreen()
                return true
            }
        }
        return true
    }

    private fun handleScale(event: MotionEvent, right: Boolean): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resizeRight = right
                resizeStartRawX = event.rawX
                resizeStartRawY = event.rawY
                resizeBaseW = contentWidth
                resizeBaseH = contentHeight
                resizeBaseX = windowX
                veil.visibility = View.VISIBLE
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - resizeStartRawX
                val dy = event.rawY - resizeStartRawY
                val maxW = (desktopDisplay.width - activity.dpPublic(8)).coerceAtLeast(activity.dpPublic(MIN_W_DP))
                val maxH = (desktopDisplay.height - activity.dpPublic(8)).coerceAtLeast(activity.dpPublic(MIN_H_DP))
                val newW = (resizeBaseW + if (resizeRight) dx else -dx).roundToInt()
                    .coerceIn(activity.dpPublic(MIN_W_DP), maxW)
                val newH = (resizeBaseH + dy).roundToInt()
                    .coerceIn(activity.dpPublic(MIN_H_DP), maxH)
                contentWidth = newW
                contentHeight = newH
                if (!resizeRight) windowX = resizeBaseX - (newW - resizeBaseW)
                applyRootSize()
                updateAttachedLayout()
                scheduleSessionResize()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                veil.visibility = View.GONE
                clampInScreen()
                return true
            }
        }
        return true
    }

    private fun handlePill(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureDownY = event.rawY
                gestureDownX = event.rawX
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dy = event.rawY - gestureDownY
                val dx = event.rawX - gestureDownX
                when {
                    kotlin.math.abs(dy) < activity.dpPublic(12) && kotlin.math.abs(dx) < activity.dpPublic(12) -> {
                        sessionScope.launch(Dispatchers.IO) {
                            val result = windowSession.injectKeyCode(KeyEvent.KEYCODE_BACK)
                            result.onFailure { activity.logDesktopWindow("Back injection failed", it) }
                        }
                    }
                    dy < -activity.dpPublic(60) && kotlin.math.abs(dy) > kotlin.math.abs(dx) -> {
                        closeWindow()
                    }
                    dy > activity.dpPublic(60) && kotlin.math.abs(dy) > kotlin.math.abs(dx) -> {
                        maximize()
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    private fun toggleMinimized() {
        if (released) return
        minimized = !minimized
        if (minimized) {
        } else {
        }
        val showFullChrome = !minimized
        header.visibility = if (showFullChrome) View.VISIBLE else View.GONE
        content.visibility = if (showFullChrome) View.VISIBLE else View.GONE
        contentBackground.visibility = if (showFullChrome) View.VISIBLE else View.GONE
        veil.visibility = View.GONE
        bottom.visibility = View.VISIBLE
        pin.contentDescription = if (minimized) "还原" else "最小化"
        pin.text = if (minimized) "□" else "—"
        applyRootSize()
        updateAttachedLayout()
        if (showFullChrome) content.requestFocus()
    }

    private fun toggleHangUp() {
        if (released) return
        minimized = false
        if (!hanging) {
            lastWidth = contentWidth
            lastHeight = contentHeight
            contentWidth = activity.dpPublic(HANGUP_W_DP)
            contentHeight = activity.dpPublic(HANGUP_H_DP)
            hanging = true
            header.visibility = View.GONE
            bottom.visibility = View.GONE
        } else {
            contentWidth = lastWidth.coerceAtLeast(activity.dpPublic(MIN_W_DP))
            contentHeight = lastHeight.coerceAtLeast(activity.dpPublic(MIN_H_DP))
            hanging = false
            header.visibility = View.VISIBLE
            bottom.visibility = View.VISIBLE
        }
        applyRootSize()
        updateAttachedLayout()
    }

    private fun maximize() {
        if (released) return
        if (minimized) toggleMinimized()
        val targetW = desktopDisplay.width.coerceAtLeast(activity.dpPublic(MIN_W_DP))
        val targetH = desktopDisplay.height.coerceAtLeast(activity.dpPublic(MIN_H_DP))
        val targetDpi = activity.desktopDpiPublic()
        // The transition follows the requested LMO-style semantics: first
        // synchronize the private window display to the desktop geometry, then
        // repatriate the app to the DesktopShell's main display. The old window
        // session is removed only after the launch/move request is issued.
        sessionScope.launch(Dispatchers.IO) {
            runCatching { windowSession.resize(targetW, targetH, targetDpi) }
                .onFailure { activity.logDesktopWindow("pre-maximize display resize failed", it) }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
																delay(100)
                activity.migrateDesktopWindowToMainDisplay(packageName)
                closeWindow()
            }
        }
    }

    private fun applyRootSize() {
        val chromeHeight = when {
            hanging -> 0
            minimized -> bottomChrome
            else -> topChrome + bottomChrome
        }
        (content.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.width = contentWidth
            it.height = contentHeight
            it.topMargin = if (hanging || minimized) 0 else topChrome
            it.width = if (minimized) 0 else contentWidth
            it.height = if (minimized) 0 else contentHeight
            content.layoutParams = it
        }
        (contentBackground.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.width = contentWidth
            it.height = contentHeight
            it.topMargin = if (hanging || minimized) 0 else topChrome
            it.width = if (minimized) 0 else contentWidth
            it.height = if (minimized) 0 else contentHeight
            contentBackground.layoutParams = it
        }
        (veil.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.width = contentWidth
            it.height = contentHeight
            it.topMargin = if (hanging || minimized) 0 else topChrome
            it.width = if (minimized) 0 else contentWidth
            it.height = if (minimized) 0 else contentHeight
            veil.layoutParams = it
        }
        (header.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.width = contentWidth
            it.height = topChrome
            it.topMargin = 0
            header.layoutParams = it
        }
        (bottom.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.width = contentWidth
            it.height = bottomChrome
            it.topMargin = if (minimized) 0 else topChrome + contentHeight
            bottom.layoutParams = it
        }
        (exitTrigger.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.width = contentWidth
            it.height = activity.dpPublic(3)
            it.gravity = Gravity.BOTTOM
            exitTrigger.layoutParams = it
        }
        if (overlayAttached) {
            overlayParams?.let {
                it.width = contentWidth
                it.height = contentHeight + chromeHeight
                it.x = windowX
                it.y = windowY
                runCatching { overlayWm?.updateViewLayout(root, it) }
            }
        } else {
            val rootParams = (root.layoutParams as? FrameLayout.LayoutParams)
                ?: FrameLayout.LayoutParams(contentWidth, contentHeight + chromeHeight)
            rootParams.width = contentWidth
            rootParams.height = contentHeight + chromeHeight
            root.layoutParams = rootParams
        }
        root.requestLayout()
    }

    private fun scheduleSessionResize() {
        resizeJob?.cancel()
        if (!sessionStarted) return
        val w = contentWidth
        val h = contentHeight
        resizeJob = sessionScope.launch(Dispatchers.IO) {
            delay(100)
            windowSession.resize(w, h, activity.desktopDpiPublic())
        }
    }

    private fun clampInScreen() {
        val currentW = root.measuredWidth.takeIf { it > 0 }
            ?: (contentWidth)
        val currentH = root.measuredHeight.takeIf { it > 0 }
            ?: (contentHeight + if (hanging) 0 else topChrome + bottomChrome)
        val maxX = (desktopDisplay.width - currentW).coerceAtLeast(0)
        val maxY = (desktopDisplay.height - currentH).coerceAtLeast(0)
        windowX = windowX.coerceIn(-desktopDisplay.width / 2, maxX + activity.dpPublic(80))
        windowY = windowY.coerceIn(-desktopDisplay.height / 2, maxY + activity.dpPublic(80))
        updateAttachedLayout()
    }

    private fun updateAttachedLayout() {
        if (!attached || released) return
        val chromeHeight = if (hanging) 0 else topChrome + bottomChrome
        if (overlayAttached) {
            overlayParams?.let {
                it.width = contentWidth
                it.height = contentHeight + chromeHeight
                it.x = windowX
                it.y = windowY
                runCatching { overlayWm?.updateViewLayout(root, it) }
            }
        } else {
            activity.updateDesktopWindowView(
                root,
                contentWidth,
                contentHeight + chromeHeight,
                windowX,
                windowY
            )
        }
        val inputWidth = if (minimized) 0 else contentWidth
        val inputHeight = if (minimized) 0 else contentHeight
        DesktopWindowInputRouter.update(
            windowSession, desktopDisplay.displayId, windowX, windowY,
            inputWidth, inputHeight, if (hanging) 0 else topChrome
        )
    }

    private fun closeWindowFromSessionEnd() {
        if (released || closing) return
        closing = true
        sessionScope.launch(Dispatchers.Main.immediate) {
            if (released) return@launch
            released = true
            attached = false
            DesktopWindowInputRouter.unregister(windowSession)
            detachVisualWindow()
            resizeJob?.cancel()
            activity.removeDesktopWindowPublic(this@FreeformOverlayDecoration)
            runCatching { windowSession.stop() }
        }
    }

    private fun detachVisualWindow() {
        if (overlayAttached) {
            runCatching { overlayWm?.removeViewImmediate(root) }
            overlayWm = null
            overlayParams = null
            overlayAttached = false
        } else {
            runCatching {
                root.parent?.let { parent ->
                    (parent as? android.view.ViewGroup)?.removeView(root)
                }
            }
        }
    }

    private fun closeWindow() {
        if (released || closing) return
        closing = true
        val targetTaskId = windowSession.windowTaskId()
        sessionScope.launch(Dispatchers.IO) {
            // Required order: terminate the target APP process/task first via
            // Shizuku, then tear down the visual/session resources.
            val killed = runCatching {
                VirtualDisplayTaskManager.forceStopPackage(packageName)
            }.onFailure {
                Log.w("FreeformOverlayDecoration", "CLOSE_FORCE_STOP failed pkg=$packageName", it)
            }.getOrDefault(false)
            Log.i("FreeformOverlayDecoration", "CLOSE_FORCE_STOP pkg=$packageName killed=$killed")

            if (targetTaskId > 0) {
                runCatching {
                    val removed = VirtualDisplayTaskManager.removeTask(targetTaskId)
                    Log.i("FreeformOverlayDecoration", "CLOSE_TASK pkg=$packageName taskId=$targetTaskId removed=$removed")
                    if (!removed) {
                        // A task may already be gone; treat that as closed.
                        Log.i("FreeformOverlayDecoration", "CLOSE_TASK already absent pkg=$packageName taskId=$targetTaskId")
                    }
                }.onFailure {
                    Log.w("FreeformOverlayDecoration", "CLOSE_TASK failed pkg=$packageName taskId=$targetTaskId", it)
                }
            }
            withContext(Dispatchers.Main.immediate) {
                if (released) return@withContext
          						delay(200)
                released = true
                attached = false
                DesktopWindowInputRouter.unregister(windowSession)
                detachVisualWindow()
                resizeJob?.cancel()
                activity.removeDesktopWindowPublic(this@FreeformOverlayDecoration)
            }
            runCatching { windowSession.stop() }
        }
    }

    private fun releaseSilently() {
        released = true
        attached = false
        DesktopWindowInputRouter.unregister(windowSession)
        resizeJob?.cancel()
        detachVisualWindow()
        sessionScope.launch(Dispatchers.IO) { windowSession.stop() }
        activity.removeDesktopWindowPublic(this)
    }

    fun remove() = closeWindow()
}

private class ExitTriggerView(
    context: android.content.Context,
    private val onVisible: () -> Unit,
) : View(context) {
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView === this && visibility == View.VISIBLE) {
            post { onVisible() }
        }
    }
}

private class TriangleHandleView(
    context: android.content.Context,
    private val left: Boolean,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEAF0F7.toInt()
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val size = minOf(w, h) * 0.30f
        val cx = if (left) w * 0.30f else w * 0.70f
        val cy = h * 0.50f
        val path = Path()
        if (left) {
            path.moveTo(cx + size * 0.55f, cy - size * 0.75f)
            path.lineTo(cx - size * 0.70f, cy)
            path.lineTo(cx + size * 0.55f, cy + size * 0.75f)
        } else {
            path.moveTo(cx - size * 0.55f, cy - size * 0.75f)
            path.lineTo(cx + size * 0.70f, cy)
            path.lineTo(cx - size * 0.55f, cy + size * 0.75f)
        }
        path.close()
        canvas.drawPath(path, paint)
    }
}

