package com.ynk.virtualdisplay.ui.desktop

import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.PixelFormat
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.Display
import android.view.WindowManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
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

/**
 * Lightweight desktop shell rendered INSIDE the 1920x1080 virtual display.
 * It intentionally does not mirror the phone UI. It provides a wallpaper,
 * a small launcher/taskbar and a stable background beneath Lineage/AOSP
 * Full-screen application launcher rendered inside the virtual display.
 */
class DesktopShellActivity : ComponentActivity() {

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
            background = wallpaperDrawable()
        }

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

        root.addView(taskbar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(68)
        ))

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
            val list = LinearLayout(this@DesktopShellActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            scroll.addView(list, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            ordered.forEach { app ->
                val isRecent = app.packageName in recentSet
                val label = if (isRecent) "★  ${app.name}" else app.name
                val item = button(label) {
                    popup.dismiss()
                    launchRemoteApp(app.packageName)
                }
                item.maxLines = 1
                item.ellipsize = android.text.TextUtils.TruncateAt.END
                item.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                item.setPadding(dp(18), 0, dp(18), 0)
                list.addView(item, LinearLayout.LayoutParams(dp(520), dp(54)).apply {
                    bottomMargin = dp(5)
                })
            }

            popup = PopupWindow(
                scroll,
                minOf(dp(560), resources.displayMetrics.widthPixels - dp(48)),
                minOf(dp(850), resources.displayMetrics.heightPixels - dp(120)),
                true
            ).apply {
                elevation = dp(18).toFloat()
                isOutsideTouchable = true
            }
            popup.showAtLocation(
                window.decorView,
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

    private suspend fun refreshTaskbar() {
        if (!desktopWindowFocused || !window.decorView.hasWindowFocus()) return
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
                val item = button(label) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        if (!VirtualDisplayTaskManager.focusTask(task.taskId)) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@DesktopShellActivity, "无法切换到 ${label}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                item.maxLines = 1
                item.ellipsize = android.text.TextUtils.TruncateAt.END
                container.addView(item, LinearLayout.LayoutParams(dp(150), dp(48)).apply {
                    marginEnd = dp(6)
                })
            }
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
        super.onDestroy()
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

    private fun wallpaperDrawable(): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF101826.toInt(), 0xFF182A42.toInt(), 0xFF0B1220.toInt())
        )

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
