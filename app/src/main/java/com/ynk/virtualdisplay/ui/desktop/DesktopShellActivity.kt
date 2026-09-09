package com.ynk.virtualdisplay.ui.desktop

import android.content.Intent
import android.app.AlertDialog
import android.app.ActivityOptions
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.PixelFormat
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.Display
import android.view.MotionEvent
import android.view.WindowManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import android.widget.PopupWindow
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.GridView
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.content.pm.LauncherApps.ShortcutQuery
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.ynk.virtualdisplay.domain.DisplayInteractor
import com.ynk.virtualdisplay.data.repository.RecentAppHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.regex.Pattern
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.LinkedHashSet
import android.content.SharedPreferences

/**
 * Lightweight desktop shell rendered INSIDE the 1920x1080 virtual display.
 * It intentionally does not mirror the phone UI. It provides a wallpaper,
 * a small launcher/taskbar and a stable background beneath Lineage/AOSP
 * Full-screen application launcher rendered inside the virtual display.
 */
class DesktopShellActivity : ComponentActivity() {

    companion object {
        const val PREFS_NAME = "desktop_shell_preferences"
        const val PREF_DESKTOP_WIDTH = "desktop_width"
        const val PREF_DESKTOP_HEIGHT = "desktop_height"
        const val PREF_DESKTOP_DPI = "desktop_dpi"

        private val instances = ConcurrentHashMap<Int, WeakReference<DesktopShellActivity>>()

        /**
         * Show the EXISTING desktop Dock on the requested virtual display.
         * The DisplayActivity is on the physical display, so it must never
         * create a PopupWindow there. We route the command to the DesktopShell
         * instance that is already running on the virtual display and move the
         * very same Dock view into a display-local overlay window.
         */
        fun showDockForDisplay(displayId: Int) {
            instances[displayId]?.get()?.let { activity ->
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        activity.showDockOverlay()
                    }
                }
            }
        }
    }

    internal val displayInteractor: DisplayInteractor by inject()
    // Retained only for backward-compatible helper code; Freeform decoration is no longer started.
    private val desktopWindows = LinkedHashSet<FreeformOverlayDecoration>()
    internal var desktopDisplayId: Int = Display.DEFAULT_DISPLAY
    private var taskbarAppsContainer: LinearLayout? = null
    private var taskbarMonitorJob: Job? = null
    private var renderedTaskbarSignature: List<Int> = emptyList()
    private var cachedDrawerApps: List<com.ynk.virtualdisplay.protocol.DeviceMessage.AppEntry> = emptyList()
    private var cachedDrawerAppsAt: Long = 0L
    private var cachedRecentPackages: List<String> = emptyList()
    private var cachedRecentPackagesAt: Long = 0L
    private var drawerOverlayWindowManager: WindowManager? = null
    private var drawerOverlayRoot: FrameLayout? = null
    private var drawerOverlayParams: WindowManager.LayoutParams? = null
    private var drawerExpanded = false
    private var drawerLastHoverY = Float.NaN
    private var drawerLastHoverX = Float.NaN
    @Volatile private var desktopWindowFocused = false
    private var portraitWindowSerial = 0
    private var landscapeWindowSerial = 0
    private var desktopRoot: View? = null
    private var desktopWindowLayer: FrameLayout? = null
    private var desktopTaskbar: View? = null
    private var dockOverlayWindowManager: WindowManager? = null
    private var dockOverlayAttached = false
    private val desktopPrefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }
    private val wallpaperPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            desktopPrefs.edit().putString("wallpaper_uri", uri.toString()).putString("wallpaper_mode", "image").apply()
            applyWallpaper()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        desktopDisplayId = display?.displayId ?: intent.getIntExtra("display_id", Display.DEFAULT_DISPLAY)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(24), dp(18), dp(24), dp(18))
            background = createWallpaperDrawable()
            setOnContextClickListener { showDesktopContextMenu(it); true }
            setOnTouchListener { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS &&
                    event.actionButton == MotionEvent.BUTTON_SECONDARY) {
                    showDesktopContextMenu(v)
                    true
                } else false
            }
            setOnGenericMotionListener { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS &&
                    event.actionButton == MotionEvent.BUTTON_SECONDARY) {
                    showDesktopContextMenu(v)
                    true
                } else false
            }
        }
        desktopRoot = root

        // All freeform decorations live inside the DesktopShell view hierarchy.
        // This guarantees they are rendered on the same virtual display and
        // avoids cross-window token/display issues on MIUI.
        val windowLayer = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            isClickable = false
            isFocusable = false
        }
        desktopWindowLayer = windowLayer

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(spacer)

        val taskbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = rounded(0xE01B1F26.toInt(), 18f)
            elevation = dp(8).toFloat()
        }

        lateinit var launcher: TextView
        launcher = button("☰  应用") { showAppLauncher() }
        taskbar.addView(launcher, LinearLayout.LayoutParams(dp(150), dp(52)).apply {
            marginEnd = dp(10)
        })

        val taskScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            background = null
        }
        taskbarAppsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        taskScroll.addView(taskbarAppsContainer, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)
        ))
        taskbar.addView(taskScroll, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
            marginEnd = dp(8)
        })

        val clock = TextView(this).apply {
            text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            alpha = 0.9f
        }
        taskbar.addView(clock, LinearLayout.LayoutParams(dp(90), dp(52)))

        desktopTaskbar = taskbar
        root.addView(taskbar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(68)
        ))

        instances[desktopDisplayId] = WeakReference(this)

        // Shell content is the background/taskbar; the window layer is drawn
        // over it so desktop windows can overlap the taskbar/background just
        // like LMO's decoration layer.
        val shell = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        shell.addView(root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        shell.addView(windowLayer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(shell)
    }

    internal fun addDesktopWindowView(view: View, width: Int, height: Int, x: Int, y: Int) {
        val layer = desktopWindowLayer ?: return
        if (view.parent == layer) return
        view.layoutParams = FrameLayout.LayoutParams(width, height).apply {
            leftMargin = x
            topMargin = y
        }
        layer.addView(view)
        view.bringToFront()
    }

    internal fun updateDesktopWindowView(view: View, width: Int, height: Int, x: Int, y: Int) {
        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.width = width
        lp.height = height
        lp.leftMargin = x
        lp.topMargin = y
        view.layoutParams = lp
    }

    internal fun bringDesktopWindowToFront(view: View) {
        if (view.parent === desktopWindowLayer) view.bringToFront()
    }

    internal fun removeDesktopWindowView(view: View) {
        val layer = desktopWindowLayer
        if (layer != null && view.parent === layer) layer.removeView(view)
    }


    internal fun desktopDpiPublic(): Int = desktopPrefs.getInt(PREF_DESKTOP_DPI, 160)

    override fun onDestroy() {
        // Release desktop-window resources before the shell Activity goes away.
        closeAppDrawer()
        taskbarMonitorJob?.cancel()
        taskbarMonitorJob = null
        desktopWindows.toList().forEach { runCatching { it.remove() } }
        desktopWindows.clear()
        instances.remove(desktopDisplayId)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Task discovery is allowed only while the DesktopShell itself is the
        // focused/visible window. This keeps dumpsys/shizuku work completely
        // out of the active full-screen application/video path.
        if (window.decorView.hasWindowFocus()) {
            desktopWindowFocused = true
            lifecycleScope.launch {
                refreshTaskbar()
                startTaskbarMonitor()
            }
        }
    }

    override fun onPause() {
        desktopWindowFocused = false
        if (!dockOverlayAttached) {
            taskbarMonitorJob?.cancel()
            taskbarMonitorJob = null
        }
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        desktopWindowFocused = hasFocus
        if (hasFocus && !isFinishing && !isDestroyed) {
            taskbarMonitorJob?.cancel()
            taskbarMonitorJob = lifecycleScope.launch {
                // Query immediately when we really return to the desktop.
                refreshTaskbar()
                startTaskbarMonitor()
            }
        } else {
            taskbarMonitorJob?.cancel()
            taskbarMonitorJob = null
        }
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The virtual display launches this Activity as its HOME task. Reassert
        // the full desktop content whenever WindowManager re-delivers the HOME
        // intent after a task switch.
        window.decorView.post {
            if (window.decorView.width > 0 && window.decorView.height > 0) {
                window.decorView.requestLayout()
                window.decorView.invalidate()
            }
        }
    }

    /**
     * Desktop dock app drawer. Keep this in sync with the normal-mode app drawer:
     * use the daemon/RPC app list (not the phone's PackageManager), preserve
     * recent-app ordering, and do not arbitrarily truncate the application list.
     */
    private fun showAppLauncher() {
        if (isFinishing || isDestroyed || drawerOverlayRoot != null) return
        if (!ensureOverlayPermissionSilently()) return
        val targetDisplay = display ?: return
        val displayContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            createDisplayContext(targetDisplay).createWindowContext(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null
            )
        } else {
            createDisplayContext(targetDisplay)
        }
        val wm = displayContext.getSystemService(WindowManager::class.java) ?: return

        val fullWidth = minOf(dp(580), targetDisplay.width - dp(32)).coerceAtLeast(dp(320))
        val rowHeight = dp(84)
        val fullHeight = minOf(dp(850), targetDisplay.height - dp(120)).coerceAtLeast(rowHeight)

        val container = FrameLayout(displayContext).apply {
            background = roundedPublic(0xF0161A20.toInt(), 20f)
            clipChildren = true
            clipToPadding = true
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val recentRow = LinearLayout(displayContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), 0)
        }
        val scroll = android.widget.HorizontalScrollView(displayContext).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(recentRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, rowHeight - dp(12)))
        }
        container.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, rowHeight - dp(12), Gravity.TOP
        ))

        fun addRecentItem(pkg: String, index: Int) {
            val item = createAppItem(pkg, runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            }.getOrElse { pkg.substringAfterLast('.') }, compact = true) {
                closeAppDrawer()
                launchRemoteApp(pkg)
            };
            recentRow.addView(item, LinearLayout.LayoutParams(
                if (showAppIcons()) dp(62) else dp(160), rowHeight - dp(18)
            ).apply { marginEnd = dp(6) })
        }

        val recent = cachedRecentPackages.distinct().filter { it != packageName }
        if (recent.isEmpty()) {
            recentRow.addView(TextView(displayContext).apply {
                text = "最近应用"
                setTextColor(0xFFCBD2DC.toInt())
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            recent.take(12).forEachIndexed { index, pkg -> addRecentItem(pkg, index) }
        }

        val params = WindowManager.LayoutParams(
            fullWidth,
            rowHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = dp(26)
            y = dp(94)
            title = "VirtualDisplayDesktopAppDrawer-$desktopDisplayId"
        }

        drawerExpanded = false
        drawerLastHoverX = Float.NaN
        drawerLastHoverY = Float.NaN
        drawerOverlayRoot = container
        drawerOverlayWindowManager = wm
        drawerOverlayParams = params

        fun expandDrawer() {
            if (drawerExpanded || drawerOverlayRoot == null) return
            drawerExpanded = true
            params.height = fullHeight
            runCatching { wm.updateViewLayout(container, params) }
            populateFullDrawer(container, fullWidth, fullHeight)
        }

        fun leftThroughTopEdge(event: MotionEvent): Boolean {
            if (drawerExpanded) return false
            val topSlop = dp(10).toFloat()
            val lastY = if (drawerLastHoverY.isNaN()) event.y else drawerLastHoverY
            val currentY = event.y
            // Expand only when the pointer leaves through the TOP edge. Do not
            // expand for side/bottom exit, ACTION_OUTSIDE, or a simple click.
            return lastY <= topSlop && currentY <= topSlop
        }

        container.setOnHoverListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER -> {
                    drawerLastHoverX = event.x
                    drawerLastHoverY = event.y
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    val shouldExpand = leftThroughTopEdge(event)
                    Log.d("DesktopDrawer", "HOVER_EXIT x=${event.x} y=${event.y} lastY=$drawerLastHoverY expand=$shouldExpand")
                    if (shouldExpand) expandDrawer()
                    drawerLastHoverX = Float.NaN
                    drawerLastHoverY = Float.NaN
                }
                MotionEvent.ACTION_OUTSIDE -> {
                    Log.d("DesktopDrawer", "HOVER_OUTSIDE ignored in collapsed drawer")
                }
            }
            false
        }
        container.setOnGenericMotionListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER -> {
                    drawerLastHoverX = event.x
                    drawerLastHoverY = event.y
                    false
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    val shouldExpand = leftThroughTopEdge(event)
                    Log.d("DesktopDrawer", "GENERIC_HOVER_EXIT x=${event.x} y=${event.y} lastY=$drawerLastHoverY expand=$shouldExpand")
                    if (shouldExpand) expandDrawer()
                    drawerLastHoverX = Float.NaN
                    drawerLastHoverY = Float.NaN
                    true
                }
                else -> false
            }
        }
        container.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                Log.d("DesktopDrawer", "TOUCH_OUTSIDE expanded=$drawerExpanded")
                closeAppDrawer()
                true
            } else false
        }

        runCatching { wm.addView(container, params) }
            .onFailure {
                drawerOverlayRoot = null
                drawerOverlayWindowManager = null
                drawerOverlayParams = null
                Log.w("DesktopShell", "Failed to show app drawer overlay", it)
            }

        // Recent row is cache-only. Start loading the full app list in the background.
        lifecycleScope.launch(Dispatchers.IO) {
            val cacheFresh = cachedDrawerApps.isNotEmpty() && System.currentTimeMillis() - cachedDrawerAppsAt < 30_000L
            if (!cacheFresh) {
                val result = displayInteractor.listApps()
                result.onSuccess {
                    cachedDrawerApps = it
                    cachedDrawerAppsAt = System.currentTimeMillis()
                }
            }
        }
    }

    private fun populateFullDrawer(container: FrameLayout, drawerWidth: Int, drawerHeight: Int) {
        val existing = container.getChildAt(1)
        if (existing != null) {
            container.removeViewAt(1)
        }
        val apps = cachedDrawerApps.filter { it.packageName != packageName }
        if (apps.isEmpty()) {
            container.addView(TextView(container.context).apply {
                text = "正在加载应用…"
                setTextColor(0xFFCBD2DC.toInt())
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, drawerHeight - dp(92), Gravity.TOP).apply {
                topMargin = dp(86)
            })
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { displayInteractor.listApps() }
                result.onSuccess {
                    cachedDrawerApps = it
                    cachedDrawerAppsAt = System.currentTimeMillis()
                    if (drawerOverlayRoot === container && drawerExpanded) {
                        populateFullDrawer(container, drawerWidth, drawerHeight)
                    }
                }
            }
            return
        }
        val recentSet = cachedRecentPackages.toSet()
        val ordered = apps.sortedWith(compareByDescending<com.ynk.virtualdisplay.protocol.DeviceMessage.AppEntry> { it.packageName in recentSet }.thenBy { it.name.lowercase() })
        val adapter = object : BaseAdapter() {
            override fun getCount() = ordered.size
            override fun getItem(position: Int) = ordered[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val app = ordered[position]
                val recent = app.packageName in recentSet
                return createAppItem(app.packageName, if (recent) "★  ${app.name}" else app.name, compact = false) {
                    closeAppDrawer()
                    launchRemoteApp(app.packageName)
                }
            }
        }
        val list: View = if (showAppIcons()) {
            GridView(this).apply {
                numColumns = 5
                horizontalSpacing = dp(4)
                verticalSpacing = dp(4)
                columnWidth = dp(104)
                stretchMode = GridView.NO_STRETCH
                this.adapter = adapter
                setPadding(dp(6), dp(6), dp(6), dp(6))
                clipToPadding = false
            }
        } else {
            ListView(this).apply {
                divider = null
                dividerHeight = dp(4)
                this.adapter = adapter
                setPadding(dp(6), dp(6), dp(6), dp(6))
                clipToPadding = false
            }
        }
        container.addView(list, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, drawerHeight - dp(92), Gravity.TOP
        ).apply { topMargin = dp(86) })
    }

    private fun closeAppDrawer() {
        val root = drawerOverlayRoot ?: return
        runCatching { drawerOverlayWindowManager?.removeViewImmediate(root) }
        drawerOverlayWindowManager = null
        drawerOverlayRoot = null
        drawerOverlayParams = null
        drawerExpanded = false
    }

    private fun launchRemoteApp(packageName: String) {
        val displayId = desktopDisplayId
        lifecycleScope.launch(Dispatchers.IO) {
            val existing = VirtualDisplayTaskManager.findTaskByPackage(
                this@DesktopShellActivity, displayId, packageName
            )
            if (existing != null && VirtualDisplayTaskManager.focusTask(existing.taskId)) {
                return@launch
            }

            val foreign = VirtualDisplayTaskManager.findTaskOnOtherDisplay(
                this@DesktopShellActivity, displayId, packageName
            )
            if (foreign != null && VirtualDisplayTaskManager.moveExistingTaskToDisplay(
                    this@DesktopShellActivity, foreign, displayId
                )) {
                return@launch
            }

            val result = displayInteractor.launchApp(packageName, displayId, freeform = false)
            delay(300)
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    lifecycleScope.launch(Dispatchers.IO) {
                        RecentAppHelper.addRecentApp(this@DesktopShellActivity, packageName)
                    }
                }.onFailure {
                    Toast.makeText(
                        this@DesktopShellActivity,
                        "启动失败：${it.message ?: "daemon 拒绝启动"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                refreshTaskbar()
            }
        }
    }

    internal fun migrateDesktopWindowToMainDisplay(packageName: String) {
        if (isFinishing || isDestroyed) return
        launchRemoteApp(packageName)
    }

    private fun startTaskbarMonitor() {
        taskbarMonitorJob?.cancel()
        taskbarMonitorJob = lifecycleScope.launch {
            delay(2000)
            while (isActive && (desktopWindowFocused || dockOverlayAttached) && !isFinishing && !isDestroyed) {
                if (dockOverlayAttached || (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
                    window.decorView.hasWindowFocus())) {
                    refreshTaskbar(allowWhenUnfocused = dockOverlayAttached)
                }
                delay(2000)
            }
        }
    }

    internal suspend fun refreshTaskbar(allowWhenUnfocused: Boolean = false) {
        if (!allowWhenUnfocused && (!desktopWindowFocused && !dockOverlayAttached)) return
        val now = System.currentTimeMillis()
        val taskSnapshot = withContext(Dispatchers.IO) {
            VirtualDisplayTaskManager.findTasks(this@DesktopShellActivity, desktopDisplayId)
                .filter { it.packageName != packageName }
        }
        if (now - cachedRecentPackagesAt > 5_000L) {
            val recent = withContext(Dispatchers.IO) {
                runCatching { RecentAppHelper.getRecentApps(this@DesktopShellActivity) }.getOrDefault(emptyList())
            }
            cachedRecentPackages = recent
            cachedRecentPackagesAt = now
        }
        withContext(Dispatchers.Main) {
            val container = taskbarAppsContainer ?: return@withContext
            container.removeAllViews()
            val shown = LinkedHashSet<String>()
            taskSnapshot.forEach { task ->
                if (!shown.add(task.packageName)) return@forEach
                val item = createAppItem(
                    packageName = task.packageName,
                    label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(task.packageName, 0)).toString() }
                        .getOrElse { task.packageName.substringAfterLast('.') },
                    compact = true
                ) { VirtualDisplayTaskManager.focusTask(task.taskId) }
                container.addView(item, LinearLayout.LayoutParams(
                    if (showAppIcons()) dp(58) else dp(150), dp(48)
                ).apply { marginEnd = dp(6) })
            }
            desktopWindows.filter { it.isVisible() }.forEach { win ->
                if (!shown.add(win.packageName)) return@forEach
                val item = createAppItem(packageName = win.packageName, label = win.displayLabel, compact = true) { win.bringToFront() }
                container.addView(item, LinearLayout.LayoutParams(
                    if (showAppIcons()) dp(58) else dp(150), dp(48)
                ).apply { marginEnd = dp(6) })
            }
        }
    }

    private fun showDesktopContextMenu(anchor: View) {
        val items = arrayOf(
            "修改壁纸",
            if (showAppIcons()) "应用显示：图标" else "应用显示：名称",
            "修改分辨率 / DPI",
            "刷新应用与任务栏"
        )
        AlertDialog.Builder(this)
            .setTitle("桌面")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showWallpaperMenu()
                    1 -> toggleAppPresentation()
                    2 -> showDisplaySettingsDialog()
                    3 -> lifecycleScope.launch { renderedTaskbarSignature = emptyList(); refreshTaskbar() }
                }
            }
            .show()
    }

    private fun showWallpaperMenu() {
        val currentMode = desktopPrefs.getString("wallpaper_mode", "gradient") ?: "gradient"
        val choices = arrayOf("系统渐变", "深色渐变", "纯黑", "从设备选择图片")
        val checked = when (currentMode) { "dark" -> 1; "black" -> 2; "image" -> 3; else -> 0 }
        AlertDialog.Builder(this)
            .setTitle("桌面壁纸")
            .setSingleChoiceItems(choices, checked) { dialog, which ->
                when (which) {
                    0 -> desktopPrefs.edit().putString("wallpaper_mode", "gradient").remove("wallpaper_uri").apply()
                    1 -> desktopPrefs.edit().putString("wallpaper_mode", "dark").remove("wallpaper_uri").apply()
                    2 -> desktopPrefs.edit().putString("wallpaper_mode", "black").remove("wallpaper_uri").apply()
                    3 -> { dialog.dismiss(); wallpaperPicker.launch(arrayOf("image/*")); return@setSingleChoiceItems }
                }
                dialog.dismiss()
                applyWallpaper()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyWallpaper() {
        val root = desktopRoot ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val drawable = createWallpaperDrawable()
            withContext(Dispatchers.Main) { root.background = drawable }
        }
    }

    private fun toggleAppPresentation() {
        desktopPrefs.edit().putBoolean("show_app_icons", !showAppIcons()).apply()
        renderedTaskbarSignature = emptyList()
        lifecycleScope.launch { refreshTaskbar() }
        Toast.makeText(this, if (showAppIcons()) "应用改为显示图标" else "应用改为显示名称", Toast.LENGTH_SHORT).show()
    }

    private fun showDisplaySettingsDialog() {
        val displayMetrics = resources.displayMetrics
        val currentW = runCatching { display?.mode?.physicalWidth ?: resources.displayMetrics.widthPixels }.getOrDefault(resources.displayMetrics.widthPixels)
        val currentH = runCatching { display?.mode?.physicalHeight ?: resources.displayMetrics.heightPixels }.getOrDefault(resources.displayMetrics.heightPixels)
        val currentDpi = displayMetrics.densityDpi

        val presets = arrayOf("当前: ${currentW}×${currentH} @ ${currentDpi} DPI", "1280×720 @ 160", "1920×1080 @ 160", "1920×1080 @ 240", "2560×1440 @ 160", "自定义")
        AlertDialog.Builder(this)
            .setTitle("分辨率 / DPI")
            .setItems(presets) { _, which ->
                when (which) {
                    0 -> Unit
                    1 -> resizeDesktopDisplay(1280, 720, 160)
                    2 -> resizeDesktopDisplay(1920, 1080, 160)
                    3 -> resizeDesktopDisplay(1920, 1080, 240)
                    4 -> resizeDesktopDisplay(2560, 1440, 160)
                    5 -> showCustomDisplayDialog(currentW, currentH, currentDpi)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCustomDisplayDialog(currentW: Int, currentH: Int, currentDpi: Int) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        fun field(value: Int, hint: String): EditText = EditText(this).apply {
            setText(value.toString()); this.hint = hint; inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val w = field(currentW, "宽"); val h = field(currentH, "高"); val dpi = field(currentDpi, "DPI")
        box.addView(w); box.addView(h); box.addView(dpi)
        AlertDialog.Builder(this).setTitle("自定义显示参数").setView(box)
            .setPositiveButton("应用") { _, _ ->
                val width = w.text.toString().toIntOrNull(); val height = h.text.toString().toIntOrNull(); val density = dpi.text.toString().toIntOrNull()
                if (width == null || height == null || density == null || width < 320 || height < 240 || density < 80 || density > 640) {
                    Toast.makeText(this, "参数无效", Toast.LENGTH_SHORT).show()
                } else resizeDesktopDisplay(width, height, density)
            }
            .setNegativeButton("取消", null).show()
    }

    private fun resizeDesktopDisplay(width: Int, height: Int, dpi: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = displayInteractor.resizeDisplay(desktopDisplayId, width, height, dpi)
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    desktopPrefs.edit()
                        .putInt(PREF_DESKTOP_WIDTH, width)
                        .putInt(PREF_DESKTOP_HEIGHT, height)
                        .putInt(PREF_DESKTOP_DPI, dpi)
                        .apply()
                    Toast.makeText(this@DesktopShellActivity, "已调整为 ${width}×${height} @ ${dpi} DPI", Toast.LENGTH_SHORT).show()
                    desktopRoot?.requestLayout()
                    desktopRoot?.invalidate()
                }.onFailure {
                    Toast.makeText(this@DesktopShellActivity, "调整失败：${it.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showAppIcons(): Boolean = desktopPrefs.getBoolean("show_app_icons", false)

    private fun createAppItem(packageName: String, label: String, compact: Boolean, onClick: () -> Unit): View {
        return if (!showAppIcons()) {
            button(label, onClick).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(if (compact) dp(10) else dp(18), 0, dp(10), 0)
                installAppContextGesture(this, packageName)
            }
        } else {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(0x401F242C.toInt(), 14f)
                isClickable = true
                isLongClickable = true
                setOnClickListener { onClick() }
                installAppContextGesture(this, packageName)
                val icon = ImageView(this@DesktopShellActivity).apply {
                    setImageDrawable(loadAppIcon(packageName))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }
                val iconSize = if (compact) dp(28) else dp(68)
                addView(icon, LinearLayout.LayoutParams(iconSize, iconSize))
                if (!compact) {
                    // Icon drawer: keep the enlarged icon and show a short name underneath.
                    val caption = TextView(this@DesktopShellActivity).apply {
                        text = label.removePrefix("★  ")
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                    }
                    addView(caption, LinearLayout.LayoutParams(dp(96), dp(20)).apply {
                        topMargin = dp(2)
                    })
                }
            }
        }
    }

    /**
     * Mirrors Launcher3's app icon interaction model: long-press or secondary mouse
     * click opens a context menu that combines the app's published shortcuts with
     * desktop actions. The popup is anchored to this Activity, so it stays on the
     * same virtual display rather than creating a physical-display overlay.
     */
    private fun installAppContextGesture(view: View, pkg: String) {
        view.isLongClickable = true
        view.setOnLongClickListener {
            showAppShortcutMenu(view, pkg)
            true
        }
        view.setOnContextClickListener {
            showAppShortcutMenu(view, pkg)
            true
        }
        view.setOnGenericMotionListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS &&
                (event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0
            ) {
                showAppShortcutMenu(view, pkg)
                true
            } else {
                false
            }
        }
    }

    private fun showAppShortcutMenu(anchor: View, pkg: String) {
        lifecycleScope.launch {
            val shortcuts = withContext(Dispatchers.IO) { queryPublishedShortcuts(pkg) }
            lateinit var popup: PopupWindow
            val popupContent = LinearLayout(this@DesktopShellActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = rounded(0xF0161A20.toInt(), 18f)
                minimumWidth = dp(360)
            }

            val header = LinearLayout(this@DesktopShellActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(8))
            }
            val appIcon = ImageView(this@DesktopShellActivity).apply {
                setImageDrawable(loadAppIcon(pkg))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            header.addView(appIcon, LinearLayout.LayoutParams(dp(42), dp(42)))
            val title = TextView(this@DesktopShellActivity).apply {
                text = appLabel(pkg)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            header.addView(title, LinearLayout.LayoutParams(0, dp(42), 1f))
            popupContent.addView(header)

            addContextAction(popupContent, "打开", loadAppIcon(pkg), action = {
                popup.dismiss()
                launchRemoteApp(pkg)
            })
            addContextAction(
                popupContent,
                "在自由窗口打开",
                android.R.drawable.ic_menu_view,
                action = {
                    popup.dismiss()
                    launchRemoteAppFreeform(pkg, portrait = true)
                },
                secondaryAction = {
                    popup.dismiss()
                    launchRemoteAppFreeform(pkg, portrait = false)
                }
            )
            addContextAction(popupContent, "左侧分屏打开", android.R.drawable.ic_media_previous, action = {
                popup.dismiss()
                launchRemoteAppSplit(pkg, left = true)
            })
            addContextAction(popupContent, "右侧分屏打开", android.R.drawable.ic_media_next, action = {
                popup.dismiss()
                launchRemoteAppSplit(pkg, left = false)
            })

            if (shortcuts.isNotEmpty()) {
                addContextSeparator(popupContent)
                val caption = TextView(this@DesktopShellActivity).apply {
                    text = "应用快捷方式"
                    setTextColor(0xFFB8C0CC.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(dp(10), dp(6), dp(10), dp(4))
                }
                popupContent.addView(caption)
                shortcuts.forEach { si ->
                    val text = si.shortLabel?.toString()?.takeIf { it.isNotBlank() }
                        ?: si.longLabel?.toString()?.takeIf { it.isNotBlank() }
                        ?: si.id
                    val icon: Drawable? = runCatching {
                        val launcherApps = getSystemService(LauncherApps::class.java)
                        launcherApps?.getShortcutIconDrawable(si, resources.displayMetrics.densityDpi)
                    }.getOrNull()
                    addContextAction(popupContent, text, icon ?: loadAppIcon(pkg), action = {
                        popup.dismiss()
                        launchPublishedShortcut(si)
                    })
                }
            }

            addContextSeparator(popupContent)
            addContextAction(popupContent, "关闭应用", android.R.drawable.ic_menu_close_clear_cancel, action = {
                popup.dismiss()
                closeAppTask(pkg)
            })
            addContextAction(popupContent, "应用信息", android.R.drawable.ic_menu_info_details, action = {
                popup.dismiss()
                showAppInfo(pkg)
            })

            popup = PopupWindow(
                popupContent,
                minOf(dp(430), targetDisplayWidthForPopup() - dp(32)),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            ).apply {
                elevation = dp(32).toFloat()
                isOutsideTouchable = true
                inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                }
                setClippingEnabled(false)
                popupContent.elevation = dp(32).toFloat()
            }

            popupContent.measure(
                View.MeasureSpec.makeMeasureSpec(minOf(dp(430), targetDisplayWidthForPopup() - dp(32)), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(1200), View.MeasureSpec.AT_MOST)
            )
            val loc = IntArray(2)
            anchor.getLocationOnScreen(loc)
            val popupWidth = popupContent.measuredWidth.coerceAtLeast(1)
            val popupHeight = popupContent.measuredHeight.coerceAtLeast(1)
            val screenWidth = desktopWindowLayer?.width?.takeIf { it > 0 } ?: targetDisplayWidthForPopup()
            val screenHeight = desktopWindowLayer?.height?.takeIf { it > 0 } ?: (display?.height ?: resources.displayMetrics.heightPixels)
            val margin = dp(12)
            val left = loc[0].coerceIn(margin, (screenWidth - popupWidth - margin).coerceAtLeast(margin))
            val belowTop = (loc[1] + anchor.height + dp(6)).coerceIn(margin, (screenHeight - popupHeight - margin).coerceAtLeast(margin))
            val fitsBelow = belowTop + popupHeight <= screenHeight - margin
            val top = if (fitsBelow) {
                belowTop
            } else {
                (loc[1] - popupHeight - dp(6)).coerceAtLeast(margin)
            }
            Log.d("DesktopContextMenu", "SHOW pkg=$pkg anchor=${anchor.javaClass.simpleName} x=${loc[0]} y=${loc[1]} displayId=$desktopDisplayId popup=${popupWidth}x$popupHeight screen=${screenWidth}x$screenHeight top=$top fitsBelow=$fitsBelow")
            runCatching {
                popup.showAtLocation(window.decorView, Gravity.TOP or Gravity.START, left, top)
                Log.d("DesktopContextMenu", "SHOWN pkg=$pkg left=$left top=$top overlayType=${WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY}")
            }.onFailure {
                Log.e("DesktopContextMenu", "SHOW_FAILED pkg=$pkg", it)
            }
        }
    }

    private fun targetDisplayWidthForPopup(): Int =
        display?.width?.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels

    private fun addContextSeparator(parent: LinearLayout) {
        val v = View(this).apply {
            setBackgroundColor(0x333A4350)
        }
        parent.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            setMargins(dp(8), dp(5), dp(8), dp(5))
        })
    }

    private fun addContextAction(
        parent: LinearLayout,
        text: String,
        icon: Any?,
        action: () -> Unit,
        secondaryAction: (() -> Unit)? = null,
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isLongClickable = secondaryAction != null
            background = rounded(0x281F2630.toInt(), 12f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { action() }
            if (secondaryAction != null) {
                setOnContextClickListener {
                    secondaryAction()
                    true
                }
                setOnGenericMotionListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS &&
                        (event.actionButton == MotionEvent.BUTTON_SECONDARY ||
                            (event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0)
                    ) {
                        secondaryAction()
                        true
                    } else {
                        false
                    }
                }
                setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS &&
                        event.actionButton == MotionEvent.BUTTON_SECONDARY) {
                        secondaryAction()
                        true
                    } else {
                        false
                    }
                }
            }
        }
        val iv = ImageView(this).apply {
            when (icon) {
                is Drawable -> setImageDrawable(icon)
                is Int -> setImageResource(icon)
            }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        row.addView(iv, LinearLayout.LayoutParams(dp(34), dp(34)))
        val label = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(12), 0, dp(6), 0)
        }
        row.addView(label, LinearLayout.LayoutParams(0, dp(46), 1f))
        parent.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(50)
        ).apply {
            setMargins(0, dp(2), 0, dp(2))
        })
    }

    private fun queryPublishedShortcuts(pkg: String): List<ShortcutInfo> {
        val launcherApps = getSystemService(LauncherApps::class.java) ?: return emptyList()
        val query = ShortcutQuery()
            .setPackage(pkg)
            .setQueryFlags(
                ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    ShortcutQuery.FLAG_MATCH_MANIFEST or
                    ShortcutQuery.FLAG_MATCH_PINNED
            )
        return runCatching {
            launcherApps.getShortcuts(query, android.os.Process.myUserHandle()).orEmpty()
                .filter { it.isEnabled }
                .sortedBy { it.rank }
        }.getOrElse {
            Log.w("DesktopShell", "Unable to query shortcuts for $pkg", it)
            emptyList()
        }
    }

    private fun launchPublishedShortcut(info: ShortcutInfo) {
        val launcherApps = getSystemService(LauncherApps::class.java) ?: return
        val options = makeVirtualDisplayLaunchOptions(freeform = false, anchor = null)
        runCatching {
            launcherApps.startShortcut(info, null, options.toBundle())
            lifecycleScope.launch(Dispatchers.IO) {
                RecentAppHelper.addRecentApp(this@DesktopShellActivity, info.`package`)
            }
        }.onFailure {
            Log.e("DesktopShell", "Failed to launch deep shortcut ${info.id}", it)
            Toast.makeText(this, "快捷方式启动失败：${it.message ?: "未知错误"}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun makeVirtualDisplayLaunchOptions(freeform: Boolean, anchor: View?): ActivityOptions =
        ActivityOptions.makeBasic().apply {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                setLaunchDisplayId(desktopDisplayId)
            }
        }

    private fun launchRemoteAppFreeform(packageName: String, portrait: Boolean) {
        if (!ensureOverlayPermissionSilently()) {
            Toast.makeText(this, "需要悬浮窗权限才能显示桌面窗口装饰", Toast.LENGTH_SHORT).show()
            return
        }
        val dockHeight = dp(68)
        val availableOuterHeight = (display?.height ?: resources.displayMetrics.heightPixels) - dockHeight
        val topChrome = dp(40)
        val bottomChrome = dp(32)
        val minWidth = dp(280)
        val minHeight = dp(220)
        val screenWidth = display?.width ?: resources.displayMetrics.widthPixels
        val screenHeight = display?.height ?: resources.displayMetrics.heightPixels

        val contentWidth: Int
        val contentHeight: Int
        val x: Int
        val y: Int
        if (portrait) {
            contentWidth = dp(480).coerceAtMost((screenWidth - dp(24)).coerceAtLeast(minWidth))
            contentHeight = (availableOuterHeight - topChrome - bottomChrome).coerceAtLeast(minHeight)
            val maxX = (screenWidth - contentWidth).coerceAtLeast(0)
            x = if (maxX == 0) 0 else ((portraitWindowSerial++ * dp(48)) % (maxX + 1))
            y = 0
        } else {
            contentWidth = dp(900).coerceAtMost((screenWidth - dp(24)).coerceAtLeast(minWidth))
            contentHeight = dp(600).coerceAtMost((availableOuterHeight - topChrome - bottomChrome).coerceAtLeast(minHeight))
            val maxX = (screenWidth - contentWidth).coerceAtLeast(0)
            val maxY = (availableOuterHeight - (topChrome + contentHeight + bottomChrome)).coerceAtLeast(0)
            val step = dp(48)
            x = if (maxX == 0) 0 else ((landscapeWindowSerial * step) % (maxX + 1))
            y = if (maxY == 0) 0 else ((landscapeWindowSerial++ * step) % (maxY + 1))
        }

        lifecycleScope.launch(Dispatchers.Main.immediate) {
            val window = FreeformOverlayDecoration(
                this@DesktopShellActivity,
                packageName,
                initialX = x,
                initialY = y,
                initialContentWidth = contentWidth,
                initialContentHeight = contentHeight,
                portraitMode = portrait,
            )
            desktopWindows.add(window)
            window.show()
            lifecycleScope.launch(Dispatchers.IO) {
                RecentAppHelper.addRecentApp(this@DesktopShellActivity, packageName)
            }
            refreshTaskbar(allowWhenUnfocused = true)
        }
    }

    private fun launchRemoteAppSplit(packageName: String, left: Boolean) {
        if (!ensureOverlayPermissionSilently()) {
            Toast.makeText(this, "需要悬浮窗权限才能显示桌面窗口装饰", Toast.LENGTH_SHORT).show()
            return
        }
        val dockHeight = dp(68)
        val topChrome = dp(40)
        val bottomChrome = dp(32)
        val screenWidth = display?.width ?: resources.displayMetrics.widthPixels
        val screenHeight = display?.height ?: resources.displayMetrics.heightPixels
        val halfWidth = (screenWidth / 2).coerceAtLeast(dp(280))
        val availableOuterHeight = (screenHeight - dockHeight).coerceAtLeast(topChrome + bottomChrome + dp(220))
        val contentHeight = (availableOuterHeight - topChrome - bottomChrome).coerceAtLeast(dp(220))
        lifecycleScope.launch(Dispatchers.Main.immediate) {
            val window = FreeformOverlayDecoration(
                this@DesktopShellActivity,
                packageName,
                initialX = if (left) 0 else (screenWidth - halfWidth).coerceAtLeast(0),
                initialY = 0,
                initialContentWidth = halfWidth,
                initialContentHeight = contentHeight,
                portraitMode = true,
                splitMode = true,
            )
            desktopWindows.add(window)
            window.show()
            lifecycleScope.launch(Dispatchers.IO) {
                RecentAppHelper.addRecentApp(this@DesktopShellActivity, packageName)
            }
            refreshTaskbar(allowWhenUnfocused = true)
        }
    }

    internal fun killDesktopWindowTask(taskId: Int) {
        if (taskId <= 0) return
        lifecycleScope.launch(Dispatchers.IO) {
            VirtualDisplayTaskManager.removeTask(taskId)
        }
    }

    private fun closeAppTask(packageName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                runShizuku("am force-stop $packageName")
            }.onFailure {
                Log.e("DesktopShell", "关闭应用失败", it)
            }
        }
    }

    private fun showAppInfo(packageName: String) {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
    }.getOrDefault(pkg)

    private fun loadAppIcon(pkg: String): android.graphics.drawable.Drawable {
        return runCatching { packageManager.getApplicationIcon(pkg) }.getOrElse {
            getDrawable(android.R.drawable.sym_def_app_icon)!!
        }
    }

    /** Execute a privileged shell command through Shizuku. */
    internal fun runShizuku(command: String): String {
        val method: Method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        method.isAccessible = true
        val process = method.invoke(
            null, arrayOf("sh", "-c", command), null, null
        ) as java.lang.Process
        val out = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        process.waitFor()
        return out
    }

    private fun ensureOverlayPermissionSilently(): Boolean {
        if (Settings.canDrawOverlays(this)) return true
        if (!Shizuku.pingBinder()) return false
        runCatching {
            runShizuku("appops set $packageName SYSTEM_ALERT_WINDOW allow")
        }.onFailure {
            Log.w("DesktopShell", "Unable to grant overlay permission via Shizuku", it)
        }
        return Settings.canDrawOverlays(this)
    }

    /** Move the existing taskbar into a display-local overlay without rebuilding it. */
    private fun showDockOverlay() {
        val dock = desktopTaskbar ?: return
        if (dockOverlayAttached) {
            dock.visibility = View.VISIBLE
            lifecycleScope.launch { refreshTaskbar(allowWhenUnfocused = true) }
            return
        }
        if (!ensureOverlayPermissionSilently()) return

        val parent = dock.parent as? ViewGroup
        val targetDisplay = display ?: return
        val displayContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            createDisplayContext(targetDisplay).createWindowContext(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null
            )
        } else createDisplayContext(targetDisplay)
        val wm = displayContext.getSystemService(WindowManager::class.java) ?: return
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(68),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            title = "VirtualDisplayDesktopDock-$desktopDisplayId"
        }
        parent?.removeView(dock)
        dock.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                hideDockOverlay()
                true
            } else false
        }
        runCatching {
            wm.addView(dock, params)
            dockOverlayWindowManager = wm
            dockOverlayAttached = true
            dock.visibility = View.VISIBLE
            lifecycleScope.launch { refreshTaskbar(allowWhenUnfocused = true) }
        }.onFailure {
            dock.setOnTouchListener(null)
            parent?.addView(dock, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(68)
            ))
            Log.w("DesktopShell", "Failed to show virtual-display Dock overlay", it)
        }
    }

    private fun hideDockOverlay() {
        val dock = desktopTaskbar ?: return
        if (!dockOverlayAttached) return
        dock.setOnTouchListener(null)
        runCatching { dockOverlayWindowManager?.removeViewImmediate(dock) }
        dockOverlayWindowManager = null
        dockOverlayAttached = false
        val root = desktopRoot as? ViewGroup
        if (dock.parent == null && root != null) {
            root.addView(dock, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(68)
            ))
        }
    }

    private fun button(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        background = rounded(0x401F242C.toInt(), 14f)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun createWallpaperDrawable(): android.graphics.drawable.Drawable {
        val mode = desktopPrefs.getString("wallpaper_mode", "gradient") ?: "gradient"
        if (mode == "image") {
            val uri = desktopPrefs.getString("wallpaper_uri", null)?.let { Uri.parse(it) }
            if (uri != null) {
                val bmp = runCatching { contentResolver.openInputStream(uri).use { input -> BitmapFactory.decodeStream(input) } }.getOrNull()
                if (bmp != null) return CenterCropBitmapDrawable(bmp)
            }
        }
        return when (mode) {
            "dark" -> android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF05070B.toInt(), 0xFF111827.toInt(), 0xFF020308.toInt()))
            "black" -> android.graphics.drawable.ColorDrawable(Color.BLACK)
            else -> android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF101826.toInt(), 0xFF182A42.toInt(), 0xFF0B1220.toInt()))
        }
    }

    private class CenterCropBitmapDrawable(private val bitmap: Bitmap) : android.graphics.drawable.Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        override fun draw(canvas: Canvas) {
            if (bitmap.width <= 0 || bitmap.height <= 0 || bounds.width() <= 0 || bounds.height() <= 0) return
            val scale = maxOf(bounds.width().toFloat() / bitmap.width, bounds.height().toFloat() / bitmap.height)
            val drawW = bitmap.width * scale
            val drawH = bitmap.height * scale
            val left = bounds.left + (bounds.width() - drawW) / 2f
            val top = bounds.top + (bounds.height() - drawH) / 2f
            val dst = android.graphics.RectF(left, top, left + drawW, top + drawH)
            canvas.drawBitmap(bitmap, null, dst, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth(): Int = bitmap.width
        override fun getIntrinsicHeight(): Int = bitmap.height
    }

    internal val displayInteractorPublic: DisplayInteractor get() = displayInteractor
    internal fun dpPublic(value: Int): Int = dp(value)
    internal fun roundedPublic(color: Int, radiusDp: Float): android.graphics.drawable.Drawable = rounded(color, radiusDp)
    internal fun ensureOverlayPermissionSilentlyPublic(): Boolean = ensureOverlayPermissionSilently()
    internal fun logDesktopWindow(message: String, t: Throwable? = null) { Log.w("DesktopShell", message, t) }

    private fun rounded(color: Int, radiusDp: Float): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun Int.roundToInt(): Int = this
    private fun Float.roundToInt(): Int = kotlin.math.round(this).toInt()


    internal fun removeDesktopWindowPublic(window: FreeformOverlayDecoration) {
        desktopWindows.remove(window)
        lifecycleScope.launch { refreshTaskbar(allowWhenUnfocused = true) }
    }

}
