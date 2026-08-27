package com.ynk.virtualdisplay.ui.desktop

import android.content.Context
import android.graphics.Rect
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.regex.Pattern

/**
 * Tracks tasks which actually belong to one VirtualDisplay.
 *
 * We intentionally parse `dumpsys activity activities` instead of relying on
 * framework SDK task classes.  The scrcpy server in this project is built with
 * a reduced android.jar, while Shizuku can still execute the real device-side
 * dumpsys command and return the framework's textual task tree.
 */
object VirtualDisplayTaskManager {
    private const val TAG = "VirtualDisplayTaskManager"

    data class TaskInfo(
        val taskId: Int,
        val packageName: String,
        val bounds: Rect = Rect(),
        val topResumed: Boolean = false
    )

    private val displayPattern = Pattern.compile("^\\s*Display\\s*(?:#\\s*)?(\\d+)\\b.*", Pattern.CASE_INSENSITIVE)
    private val taskIdPatterns = listOf(
        Pattern.compile("^\\s*Task id #(\\d+)\\b"),
        Pattern.compile("TaskRecord\\{[^}]*\\s#(\\d+)\\b"),
        Pattern.compile("Task\\{[^}]*\\s#(\\d+)\\b"),
        Pattern.compile("^\\s*(?:\\*\\s*)?Task=(\\d+)\\b")
    )
    private val boundsPattern = Pattern.compile("(?:mBounds=Rect\\(|bounds=\\[|bounds=Rect\\()\\s*(\\d+)[, ]+(\\d+)(?:\\)|\\]\\[)(\\d+)[, ]+(\\d+)(?:\\)|\\])")
    private val taskAffinityPattern = Pattern.compile("(?:^|\\s)(?:affinity|A)=([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)")
    private val realActivityPattern = Pattern.compile("realActivity=([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*)/")
    private val activityRecordPattern = Pattern.compile("ActivityRecord\\{[^}]*\\s(?:u\\d+\\s+)?([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*)/")
    private val packageNamePattern = Pattern.compile("packageName=([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*)")

    fun findTasks(context: Context, displayId: Int): List<TaskInfo> {
        if (displayId < 0 || !Shizuku.pingBinder()) return emptyList()
        return runCatching {
            // `activities` is the task-oriented dump and exposes a stable
            // Display -> Task -> Activity hierarchy on AOSP/Lineage builds.
            val out = runShizuku("dumpsys activity activities")
            stabilizeOrder(displayId, parseTasks(context.packageName, displayId, out))
        }.onFailure {
            Log.w(TAG, "findTasks failed for display=$displayId", it)
        }.getOrDefault(emptyList())
    }

    fun findTaskByPackage(context: Context, displayId: Int, packageName: String): TaskInfo? =
        findTasks(context, displayId).firstOrNull { it.packageName == packageName }

    /** Focus the next/previous app according to the stable Dock order. */
    fun focusRelativeTask(context: Context, displayId: Int, direction: Int): Boolean {
        val tasks = findTasks(context, displayId)
        if (tasks.isEmpty()) return false

        val currentIndex = tasks.indexOfFirst { it.topResumed }
        val index = if (currentIndex >= 0) {
            Math.floorMod(currentIndex + if (direction >= 0) 1 else -1, tasks.size)
        } else {
            if (direction >= 0) 0 else tasks.lastIndex
        }
        return focusTask(tasks[index].taskId)
    }

    /** Bring an existing task to the foreground without creating another instance. */
    fun focusTask(taskId: Int): Boolean {
        if (taskId <= 0 || !Shizuku.pingBinder()) return false
        return runCatching {
            val focus = runShizuku("am task focus $taskId >/dev/null 2>&1; echo \$?").trim()
            if (focus.lineSequence().lastOrNull()?.trim() == "0") return@runCatching true

            // Some vendor Android builds do not implement `am task focus`;
            // `move-to-front` is the compatible fallback for the same task.
            val move = runShizuku("am task move-to-front $taskId >/dev/null 2>&1; echo \$?").trim()
            val ok = move.lineSequence().lastOrNull()?.trim() == "0"
            if (!ok) Log.w(TAG, "focusTask($taskId) failed: focus=$focus move=$move")
            ok
        }.getOrElse {
            Log.w(TAG, "focusTask($taskId) failed", it)
            false
        }
    }

    private val orderLock = Any()
    private val stableOrderByDisplay = mutableMapOf<Int, LinkedHashMap<Int, Long>>()
    private var nextOrder = 0L

    private fun stabilizeOrder(displayId: Int, tasks: List<TaskInfo>): List<TaskInfo> {
        synchronized(orderLock) {
            val order = stableOrderByDisplay.getOrPut(displayId) { LinkedHashMap() }
            val present = tasks.map { it.taskId }.toSet()
            order.keys.retainAll(present)
            for (task in tasks) {
                if (!order.containsKey(task.taskId)) {
                    order[task.taskId] = nextOrder++
                }
            }
            return tasks.sortedBy { order[it.taskId] ?: Long.MAX_VALUE }
        }
    }

    private fun parseTasks(ownPackage: String, displayId: Int, dump: String): List<TaskInfo> {
        val result = mutableListOf<TaskInfo>()
        var inDisplay = false
        var currentTaskId: Int? = null
        var currentPackage: String? = null
        var currentBounds: Rect? = null
        var currentTopResumed = false

        fun flush() {
            val id = currentTaskId
            val pkg = currentPackage
            if (inDisplay && id != null && !pkg.isNullOrBlank() && pkg != ownPackage) {
                result += TaskInfo(id, pkg, currentBounds ?: Rect(), currentTopResumed)
            }
            currentTaskId = null
            currentPackage = null
            currentBounds = null
            currentTopResumed = false
        }

        for (line in dump.lineSequence()) {
            // A Display block ends when dumpsys returns to column 0. Without
            // this guard, the global "ResumedActivity" section after the
            // last Display can be mistaken for tasks belonging to that Display.
            if (inDisplay && line.isNotEmpty() && !line[0].isWhitespace() &&
                !displayPattern.matcher(line).find()) {
                flush()
                inDisplay = false
                continue
            }
            var detectedDisplayId: Int? = null
            val displayMatcher = displayPattern.matcher(line)
            if (displayMatcher.find()) {
                detectedDisplayId = displayMatcher.group(1).toIntOrNull()
            }
            if (detectedDisplayId != null) {
                flush()
                inDisplay = detectedDisplayId == displayId
                continue
            }
            if (!inDisplay) continue

            var foundTaskId: Int? = null
            for (pattern in taskIdPatterns) {
                val matcher = pattern.matcher(line)
                if (matcher.find()) {
                    foundTaskId = matcher.group(1).toIntOrNull()
                    if (foundTaskId != null) break
                }
            }
            if (foundTaskId != null) {
                // Only the root task entries in a Display section represent
                // taskbar applications. Ignore nested task-like text from
                // lower-level diagnostic sections.
                val isRootTask = line.startsWith("  * Task{") ||
                    line.matches(Regex("\\s*Task id #\\d+.*"))
                if (!isRootTask) continue
                if (currentTaskId != foundTaskId) flush()
                currentTaskId = foundTaskId
                currentTopResumed = false

                // The task header commonly contains the package as A=uid:package.
                if (currentPackage == null) {
                    val headerPkg = Pattern.compile("(?:^|\\s)A=\\d+:([^\\s]+)").matcher(line)
                    if (headerPkg.find()) currentPackage = headerPkg.group(1)
                }
            }

            if (currentTaskId == null) continue

            if (line.contains("topResumedActivity=")) {
                currentTopResumed = true
                if (currentPackage == null) currentPackage = extractPackage(line)
            }

            if (currentBounds == null) {
                val bounds = boundsPattern.matcher(line)
                if (bounds.find()) {
                    currentBounds = Rect(
                        bounds.group(1).toInt(),
                        bounds.group(2).toInt(),
                        bounds.group(3).toInt(),
                        bounds.group(4).toInt()
                    )
                }
            }

            if (currentPackage == null) {
                currentPackage = extractPackage(line)
            }
        }
        flush()

        return result.distinctBy { it.taskId }
    }

    private fun extractPackage(line: String): String? {
        realActivityPattern.matcher(line).let { if (it.find()) return it.group(1) }
        activityRecordPattern.matcher(line).let { if (it.find()) return it.group(1) }
        packageNamePattern.matcher(line).let { if (it.find()) return it.group(1) }
        taskAffinityPattern.matcher(line).let { if (it.find()) return it.group(1) }
        return null
    }

    private fun runShizuku(command: String): String {
        val method: Method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        method.isAccessible = true
        val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        return BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
    }
}
