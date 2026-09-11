package com.ynk.virtualdisplay.ui.desktop

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import rikka.shizuku.Shizuku
import java.lang.reflect.Method
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.regex.Pattern

/**
 * Small in-display control bar for a generic freeform task. It is a separate
 * task on the same virtual display, so it remains visible even when the target
 * application cannot be modified.
 */
class FreeformChromeActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_DISPLAY_ID = "display_id"
    }

    private var targetPackage = ""
    private var displayId = -1
    private var sizeIndex = 1
    private val sizes = arrayOf(
        intArrayOf(120, 90, 980, 650),
        intArrayOf(120, 90, 1400, 890),
        intArrayOf(60, 50, 1860, 1030),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
            ?: intent.getStringExtra("target_package") ?: ""
        displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, intent.getIntExtra("display_id", -1))
        setupUi()
    }

    private fun setupUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(Color.argb(238, 24, 28, 34))
        }
        val title = TextView(this).apply {
            text = targetPackage.substringAfterLast('.')
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(button("−") { resize(-1) })
        root.addView(button("□") { resize(0) })
        root.addView(button("＋") { resize(1) })
        root.addView(button("×") { closeTarget() })
        setContentView(root)
    }

    private fun button(text: String, action: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        minWidth = dp(42)
        setOnClickListener { action() }
    }

    private fun resize(delta: Int) {
        if (delta < 0) sizeIndex = (sizeIndex - 1).coerceAtLeast(0)
        if (delta > 0) sizeIndex = (sizeIndex + 1).coerceAtMost(sizes.lastIndex)
        val b = sizes[sizeIndex]
        val taskId = findTargetTaskId()
        if (taskId == null) {
            toast("未找到目标窗口")
            return
        }
        shizuku("am task resize $taskId ${b[0]} ${b[1]} ${b[2]} ${b[3]}")
    }

    private fun closeTarget() {
        // The target task is hosted by its virtual display. Releasing that
        // display lets Android own the task/process cleanup policy.
        finish()
    }

    private fun findTargetTaskId(): Int? {
        val result = runCommand("dumpsys activity activities") ?: return null
        val lines = result.lines()
        val pkg = targetPackage
        val taskPattern = Pattern.compile("Task\\{[0-9a-fA-F]+\\s+#(\\d+)")
        var currentTask: Int? = null
        var hitDisplay = false
        for (line in lines) {
            val m = taskPattern.matcher(line)
            if (m.find()) {
                currentTask = m.group(1).toIntOrNull()
                hitDisplay = false
            }
            if (currentTask != null && line.contains("displayId=$displayId")) {
                hitDisplay = true
            }
            if (currentTask != null && hitDisplay && line.contains(pkg)) {
                return currentTask
            }
        }
        return null
    }

    private fun shizuku(command: String) {
        Thread {
            runCommand(command)
        }.start()
    }

    private fun runCommand(command: String): String? {
        if (!Shizuku.pingBinder()) {
            toast("Shizuku 不可用")
            return null
        }
        return try {
            val method: Method = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val out = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            process.waitFor()
            out
        } catch (t: Throwable) {
            toast("系统窗口操作失败：${t.message}")
            null
        }
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\"'\"'") + "'"

    private fun toast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
