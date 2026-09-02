package com.ynk.virtualdisplay.ui.desktop

import android.content.Intent
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.graphics.Color
import android.graphics.Rect
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
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import android.widget.PopupWindow
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

    private val displayInteractor: DisplayInteractor by inject()
    // Retained only for backward-compatible helper code; Freeform decoration is no longer started.
    private var decoration: FreeformOverlayDecoration? = null
    private var decorationJob: Job? = null
    private var currentTargetPackage: String = ""
    private var desktopDisplayId: Int = Display.DEFAULT_DISPLAY
    private var taskbarAppsContainer: LinearLayout? = null
    private var taskbarMonitorJob: Job? = null
    private var renderedTaskbarSignature: List<Int> = emptyList()
    @Volatile private var desktopWindowFocused = false
    private var desktopRoot: View? = null
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
        launcher = button("☰  应用") { showAppLauncher(launcher) }
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
        setContentView(root)
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
        taskbarMonitorJob?.cancel()
        taskbarMonitorJob = null
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
    private fun showAppLauncher(anchor: View) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { displayInteractor.listApps() }
            result.onFailure {
                Toast.makeText(
                    this@DesktopShellActivity,
                    "获取应用列表失败：${it.message ?: "未知错误"}",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val apps = result.getOrDefault(emptyList())
                .filter { it.packageName != packageName }
            if (apps.isEmpty()) {
                Toast.makeText(this@DesktopShellActivity, "没有可启动的应用", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val recent = RecentAppHelper.getRecentApps(this@DesktopShellActivity)
            val recentSet = recent.toSet()
            val recentList = apps.filter { it.packageName in recentSet }
                .sortedBy { recent.indexOf(it.packageName).takeIf { idx -> idx >= 0 } ?: Int.MAX_VALUE }
            val otherList = apps.filter { it.packageName !in recentSet }
                .sortedBy { it.name.lowercase() }
            val ordered = recentList + otherList

            lateinit var popup: PopupWindow
            val scroll = ScrollView(this@DesktopShellActivity).apply {
                isVerticalScrollBarEnabled = true
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = rounded(0xF0161A20.toInt(), 18f)
            }
            val list: ViewGroup = if (showAppIcons()) {
                android.widget.GridLayout(this@DesktopShellActivity).apply {
                    columnCount = 5
                    useDefaultMargins = false
                    alignmentMode = android.widget.GridLayout.ALIGN_MARGINS
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
            } else {
                LinearLayout(this@DesktopShellActivity).apply {
                    orientation = LinearLayout.VERTICAL
                }
            }
            scroll.addView(list, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            ordered.forEach { app ->
                val isRecent = app.packageName in recentSet
                val item = createAppItem(
                    packageName = app.packageName,
                    label = if (isRecent) "★  ${app.name}" else app.name,
                    compact = false,
                ) {
                    popup.dismiss()
                    launchRemoteApp(app.packageName)
                }
                if (showAppIcons()) {
                    val lp = android.widget.GridLayout.LayoutParams().apply {
                        width = dp(104)
                        height = dp(116)
                        setMargins(dp(4), dp(4), dp(4), dp(4))
                    }
                    list.addView(item, lp)
                } else {
                    list.addView(item, LinearLayout.LayoutParams(dp(520), dp(54)).apply {
                        bottomMargin = dp(5)
                    })
                }
            }

            val drawerWidth = if (showAppIcons()) {
                // Five cells of 104dp + 4dp margins on both sides + 8dp content padding.
                // Keep the popup just wide enough for five columns without clipping.
                minOf(dp(580), resources.displayMetrics.widthPixels - dp(32))
            } else {
                minOf(dp(560), resources.displayMetrics.widthPixels - dp(48))
            }
            popup = PopupWindow(
                scroll,
                drawerWidth,
                minOf(dp(850), resources.displayMetrics.heightPixels - dp(120)),
                true
            ).apply {
                elevation = dp(18).toFloat()
                isOutsideTouchable = true
            }
            popup.showAtLocation(
//                window.decorView,
																anchor,                
                Gravity.BOTTOM or Gravity.START,
                dp(26),
                dp(94)
            )
        }
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

    private fun startTaskbarMonitor() {
        taskbarMonitorJob?.cancel()
        taskbarMonitorJob = lifecycleScope.launch {
            delay(2000)
            while (isActive && desktopWindowFocused && !isFinishing && !isDestroyed) {
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
                    window.decorView.hasWindowFocus()) {
                    refreshTaskbar()
                }
                delay(2000)
            }
        }
    }

    private suspend fun refreshTaskbar(allowWhenUnfocused: Boolean = false) {
        if (!allowWhenUnfocused && (!desktopWindowFocused || !window.decorView.hasWindowFocus())) return
        val tasks = withContext(Dispatchers.IO) {
            VirtualDisplayTaskManager.findTasks(this@DesktopShellActivity, desktopDisplayId)
        }
        withContext(Dispatchers.Main) {
            val container = taskbarAppsContainer ?: return@withContext
            val signature = tasks.map { it.taskId }
            if (signature == renderedTaskbarSignature) return@withContext
            renderedTaskbarSignature = signature
            container.removeAllViews()
            val pm = packageManager
            tasks.forEach { task ->
                val label = runCatching {
                    pm.getApplicationInfo(task.packageName, 0).loadLabel(pm).toString()
                }.getOrDefault(task.packageName)
                val item = createAppItem(
                    packageName = task.packageName,
                    label = label,
                    compact = true,
                ) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        if (!VirtualDisplayTaskManager.focusTask(task.taskId)) {
                            withContext(Dispatchers.Main) {
                                //Toast.makeText(this@DesktopShellActivity, "无法切换到 ${label}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
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
            }
        } else {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(0x401F242C.toInt(), 14f)
                isClickable = true
                setOnClickListener { onClick() }
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

    private fun loadAppIcon(pkg: String): android.graphics.drawable.Drawable {
        return runCatching { packageManager.getApplicationIcon(pkg) }.getOrElse {
            getDrawable(android.R.drawable.sym_def_app_icon)!!
        }
    }

    private fun showDecoration(packageName: String) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "缺少悬浮窗权限，无法显示窗口控制条", Toast.LENGTH_SHORT).show()
            return
        }
        currentTargetPackage = packageName
        decoration?.remove()
        decoration = FreeformOverlayDecoration(this, desktopDisplayId, packageName).also { it.show() }
    }

    private fun startDecorationMonitor() {
        decorationJob?.cancel()
        decorationJob = lifecycleScope.launch {
            while (isActive && !isFinishing && !isDestroyed) {
                val tasks = findFreeformTasks()
                val target = tasks.firstOrNull { it.packageName == currentTargetPackage }
                    ?: tasks.firstOrNull { it.packageName != packageName }
                if (target != null && Settings.canDrawOverlays(this@DesktopShellActivity)) {
                    if (decoration == null || currentTargetPackage != target.packageName) {
                        currentTargetPackage = target.packageName
                        decoration?.remove()
                        decoration = FreeformOverlayDecoration(this@DesktopShellActivity, desktopDisplayId, target.packageName).also { it.show() }
                    }
                    decoration?.update(target.taskId, target.bounds)
                } else if (target == null) {
                    decoration?.hide()
                }
                delay(250)
            }
        }
    }

    internal fun findTargetTask(packageName: String): TaskInfo? =
        findFreeformTasks().firstOrNull { it.packageName == packageName }?.let { TaskInfo(it.taskId, it.bounds, it.packageName) }

    private fun findFreeformTasks(): List<TaskInfo> {
        if (!Shizuku.pingBinder()) return emptyList()
        return runCatching {
            val out = runShizuku("dumpsys activity containers")
            val result = mutableListOf<TaskInfo>()
            var inDisplay = false
            var currentId: Int? = null
            var currentBounds: Rect? = null
            var currentPackage: String? = null
            fun flush() {
                val id = currentId
                val b = currentBounds
                val pkg = currentPackage
                if (inDisplay && id != null && b != null && !pkg.isNullOrBlank()) {
                    result += TaskInfo(id, b, pkg)
                }
                currentId = null
                currentBounds = null
                currentPackage = null
            }
            val taskPattern = Pattern.compile("Task\\{[0-9a-fA-F]+\\s+#(\\d+).*mode=freeform")
            val boundsPattern = Pattern.compile("bounds=\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")
            for (line in out.lineSequence()) {
                val display = Regex("^\\s*\\u2514?\\s*Display (\\d+)").find(line)
                if (display != null) {
                    flush()
                    inDisplay = display.groupValues[1].toIntOrNull() == desktopDisplayId
                    continue
                }
                if (!inDisplay) continue
                val task = taskPattern.matcher(line)
                if (task.find()) {
                    flush()
                    currentId = task.group(1).toIntOrNull()
                    continue
                }
                if (currentId != null) {
                    val bounds = boundsPattern.matcher(line)
                    if (bounds.find()) {
                        currentBounds = Rect(
                            bounds.group(1).toInt(), bounds.group(2).toInt(),
                            bounds.group(3).toInt(), bounds.group(4).toInt()
                        )
                    }
                    val pkg = Regex("(?:A=\\d+:|ActivityRecord\\{[^ ]+ u\\d+ )([^/ }]+)").find(line)
                    if (pkg != null) currentPackage = pkg.groupValues[1]
                    if (currentPackage == null) {
                        val slash = Regex("([a-zA-Z0-9_]+\\.[a-zA-Z0-9_.]+)/").find(line)
                        if (slash != null) currentPackage = slash.groupValues[1]
                    }
                }
            }
            flush()
            result
        }.getOrDefault(emptyList())
    }

    internal fun runShizuku(command: String): String {
        val method: Method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        method.isAccessible = true
        val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        val out = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        process.waitFor()
        return out
    }

    data class TaskInfo(val taskId: Int, val bounds: Rect, val packageName: String)

    override fun onDestroy() {
        taskbarMonitorJob?.cancel()
        decorationJob?.cancel()
        hideDockOverlay()
        instances.remove(desktopDisplayId)?.let { ref ->
            if (ref.get() === this) instances.remove(desktopDisplayId)
        }
        super.onDestroy()
    }

    /**
     * Show the existing desktop taskbar over the foreground app on THIS
     * virtual display. The view itself is not duplicated or rebuilt.
     */
    private fun showDockOverlay() {
        val dock = desktopTaskbar ?: return
        if (dockOverlayAttached) {
            dock.visibility = View.VISIBLE
            lifecycleScope.launch { refreshTaskbar(allowWhenUnfocused = true) }
            return
        }

        val parent = dock.parent as? ViewGroup
        parent?.removeView(dock)

        // DesktopShellActivity itself is hosted by the target virtual display.
        // Still derive the WindowManager from that exact Display so the overlay
        // cannot accidentally land on the physical/default display.
        val targetDisplay = display ?: return
        val displayContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            createDisplayContext(targetDisplay).createWindowContext(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null
            )
        } else {
            createDisplayContext(targetDisplay)
        }
        val wm = displayContext.getSystemService(WindowManager::class.java) ?: return
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(68),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
																WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0
            title = "VirtualDisplayDesktopDock-$desktopDisplayId"
        }

								dock.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                hideDockOverlay()
                true
            } else {
                false
            }
        }

        runCatching {
            wm.addView(dock, params)
            dockOverlayWindowManager = wm
            dockOverlayAttached = true
            dock.visibility = View.VISIBLE
            lifecycleScope.launch { refreshTaskbar(allowWhenUnfocused = true) }
        }.onFailure {
            // Restore the exact same Dock view to the desktop root if the
            // display-local overlay cannot be attached.
            parent?.addView(dock, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(68)
            ))
            Log.w("DesktopShellActivity", "Failed to show virtual-display Dock overlay", it)
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

private class FreeformOverlayDecoration(
    private val activity: DesktopShellActivity,
    private val displayId: Int,
    private val packageName: String,
) {
    private val displayContext = activity.createDisplayContext(activity.display ?: throw IllegalStateException("Desktop display unavailable"))
    private val wm = displayContext.getSystemService(WindowManager::class.java)
    private val bar = LinearLayout(displayContext).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(8, 0, 4, 0)
        setBackgroundColor(Color.argb(245, 25, 29, 35))
        elevation = 12f
    }
    private var attached = false
    private val lp = WindowManager.LayoutParams(
        320, 46,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "VirtualDesktop-FreeformDecoration:$packageName"
    }

    init {
        val title = TextView(displayContext).apply {
            text = packageName.substringAfterLast('.')
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10, 0, 14, 0)
        }
        bar.addView(title, LinearLayout.LayoutParams(0, 46, 1f))
        bar.addView(button("−") { resize(-1) }, LinearLayout.LayoutParams(46, 46))
        bar.addView(button("□") { resize(0) }, LinearLayout.LayoutParams(46, 46))
        bar.addView(button("＋") { resize(1) }, LinearLayout.LayoutParams(46, 46))
        bar.addView(button("×") { close() }, LinearLayout.LayoutParams(46, 46))
    }

    fun show() {
        if (attached) return
        runCatching {
            wm.addView(bar, lp)
            attached = true
        }.onFailure {
            android.util.Log.e("FreeformDecoration", "add overlay failed", it)
        }
    }

    fun hide() {
        if (attached) bar.visibility = View.GONE
    }

    fun update(taskId: Int, bounds: Rect) {
        if (!attached) show()
        if (!attached) return
        lp.width = bounds.width().coerceAtLeast(320)
        lp.height = 46
        lp.x = bounds.left
        lp.y = bounds.top
        bar.visibility = View.VISIBLE
        runCatching { wm.updateViewLayout(bar, lp) }
    }

    fun remove() {
        if (!attached) return
        runCatching { wm.removeViewImmediate(bar) }
        attached = false
    }

    private fun resize(delta: Int) {
        val info = activity.findTargetTask(packageName) ?: return
        val base = info.bounds
        val target = when (delta) {
            -1 -> Rect(base.left, base.top, base.left + (base.width() * 0.82f).toInt(), base.top + (base.height() * 0.82f).toInt())
            1 -> {
                val w = (base.width() * 1.18f).toInt().coerceAtMost(1800)
                val h = (base.height() * 1.18f).toInt().coerceAtMost(1000)
                Rect(base.left, base.top, (base.left + w).coerceAtMost(1910), (base.top + h).coerceAtMost(1070))
            }
            else -> Rect(120, 90, 1400, 890)
        }
        activity.lifecycleScope.launch {
            activity.runShizuku("am task resize ${info.taskId} ${target.left} ${target.top} ${target.right} ${target.bottom}")
        }
    }

    private fun close() {
        val info = activity.findTargetTask(packageName)
        if (info != null) {
            activity.lifecycleScope.launch {
                activity.runShizuku("am task remove ${info.taskId}")
            }
        }
        hide()
    }

    private fun button(text: String, action: () -> Unit): TextView = TextView(displayContext).apply {
        this.text = text
        textSize = 18f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        isClickable = true
        setOnClickListener { action() }
    }
}

}
