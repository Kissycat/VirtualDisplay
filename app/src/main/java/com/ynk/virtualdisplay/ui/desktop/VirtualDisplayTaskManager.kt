package com.ynk.virtualdisplay.ui.desktop

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.regex.Pattern
import java.util.concurrent.FutureTask

/**
 * Reads the real device activity/task tree through Shizuku and provides
 * display-aware task switching/repatriation for the desktop launcher.
 */
object VirtualDisplayTaskManager {
    private const val TAG = "VirtualDisplayTaskManager"

    data class TaskInfo(
        val taskId: Int,
        val packageName: String,
        val displayId: Int,
        val bounds: Rect = Rect(),
        val topResumed: Boolean = false,
    )

    private val displayPattern = Pattern.compile("^\\s*Display\\s*(?:#\\s*)?(\\d+)\\b.*", Pattern.CASE_INSENSITIVE)
    private val taskIdPatterns = listOf(
        Pattern.compile("^\\s*Task id #(\\d+)\\b"),
        Pattern.compile("TaskRecord\\{[^}]*\\s#(\\d+)\\b"),
        Pattern.compile("Task\\{[^}]*\\s#(\\d+)\\b"),
        Pattern.compile("^\\s*(?:\\*\\s*)?Task=(\\d+)\\b")
    )
    private val boundsPattern = Pattern.compile("(?:mBounds=Rect\\(|bounds=\\[|bounds=Rect\\()\\s*(\\d+)[, ]+(\\d+)(?:\\)|\\]\\[)(\\d+)[, ]+(\\d+)(?:\\)|\\])")
    private val realActivityPattern = Pattern.compile("realActivity=([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*)/")
    private val activityRecordPattern = Pattern.compile("ActivityRecord\\{[^}]*\\s(?:u\\d+\\s+)?([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*)/")
    private val packageNamePattern = Pattern.compile("packageName=([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*)")

    fun findTasks(context: Context, displayId: Int): List<TaskInfo> {
        if (displayId < 0 || !Shizuku.pingBinder()) return emptyList()
        return runCatching {
            parseTasks(context.packageName, parseDump(runShizuku("dumpsys activity activities")))
                .filter { it.displayId == displayId }
                .let { stabilizeOrder(displayId, it) }
        }.onFailure {
            Log.w(TAG, "findTasks failed for display=$displayId", it)
        }.getOrDefault(emptyList())
    }

    fun findTaskByPackage(context: Context, displayId: Int, packageName: String): TaskInfo? =
        findTasks(context, displayId).firstOrNull { it.packageName == packageName }

    fun findTaskById(context: Context, displayId: Int, taskId: Int): TaskInfo? =
        findTasks(context, displayId).firstOrNull { it.taskId == taskId }

    /** Find an existing task for a package that currently lives on another display. */
    fun findTaskOnOtherDisplay(context: Context, targetDisplayId: Int, packageName: String): TaskInfo? {
        if (targetDisplayId < 0 || !Shizuku.pingBinder()) return null
        return runCatching {
            val all = parseTasks(context.packageName, parseDump(runShizuku("dumpsys activity activities")))
            all.firstOrNull { it.packageName == packageName && it.displayId >= 0 && it.displayId != targetDisplayId }
        }.onFailure {
            Log.w(TAG, "findTaskOnOtherDisplay failed package=$packageName targetDisplay=$targetDisplayId", it)
        }.getOrNull()
    }

    /**
     * Repatriate a single-instance-like application's existing task to a new
     * display without changing the normal daemon launch path.
     *
     * We deliberately do NOT use MULTIPLE_TASK here. `am start --display` uses
     * ActivityOptions.setLaunchDisplayId(), and Android's task supervisor can
     * move an existing matching task to that display on launch.
     */
    fun moveExistingTaskToDisplay(context: Context, task: TaskInfo, targetDisplayId: Int): Boolean {
        if (task.taskId <= 0 || targetDisplayId < 0 || task.displayId == targetDisplayId) return false
        if (!Shizuku.pingBinder()) return false
        if (!isLikelySingleInstance(context, task.packageName)) return false

        return runCatching {
            val cmd = buildString {
                append("am start --display ").append(targetDisplayId)
                append(" -a android.intent.action.MAIN")
                append(" -c android.intent.category.LAUNCHER")
                append(" -p '").append(quoteArg(task.packageName)).append("'")
            }
            val output = runShizuku(cmd)
            Log.i(TAG, "moveExistingTaskToDisplay package=${task.packageName} from=${task.displayId} to=$targetDisplayId output=$output")

            // Verify the task actually appeared on the requested display. This
            // prevents treating a refused/no-op launch as a successful move.
            Thread.sleep(250)
            val moved = findTaskByPackage(context, targetDisplayId, task.packageName)
            moved != null
        }.onFailure {
            Log.w(TAG, "moveExistingTaskToDisplay failed package=${task.packageName} target=$targetDisplayId", it)
        }.getOrDefault(false)
    }

    /** True for activities which explicitly behave as a singleton task/instance. */
    private fun isLikelySingleInstance(context: Context, packageName: String): Boolean {
        return runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
            val infos = context.packageManager.queryIntentActivities(intent, 0)
            if (infos.isEmpty()) return@runCatching false

            infos.any { ri ->
                val ai = ri.activityInfo
                val singletonLaunchMode = ai.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK ||
                    ai.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE ||
                    ai.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE_PER_TASK
                val documentMode = ai.documentLaunchMode
                singletonLaunchMode || documentMode == ActivityInfo.DOCUMENT_LAUNCH_NEVER
            }
        }.getOrDefault(false)
    }

    /** Focus an app relative to the stable Dock order. */
    fun focusRelativeTask(context: Context, displayId: Int, direction: Int): Boolean {
        val tasks = findTasks(context, displayId)
        if (tasks.isEmpty()) return false
        val currentIndex = tasks.indexOfFirst { it.topResumed }
        val index = if (currentIndex >= 0) {
            Math.floorMod(currentIndex + if (direction >= 0) 1 else -1, tasks.size)
        } else if (direction >= 0) 0 else tasks.lastIndex
        return focusTask(tasks[index].taskId)
    }

    /** Kill an app using Shizuku; all privileged process/task termination stays
     * on the Shizuku side rather than calling Activity APIs from the app. */
    fun forceStopPackage(packageName: String): Boolean {
        if (packageName.isBlank() || !Shizuku.pingBinder()) return false
        return runCatching {
            val output = runShizuku("am force-stop '${quoteArg(packageName)}' >/dev/null 2>&1; echo \\$?").trim()
            val ok = output.lineSequence().lastOrNull()?.trim() == "0"
            if (!ok) Log.w(TAG, "forceStopPackage($packageName) failed: $output")
            ok
        }.getOrElse {
            Log.w(TAG, "forceStopPackage($packageName) failed", it)
            false
        }
    }

    fun removeTask(taskId: Int): Boolean {
        if (taskId <= 0 || !Shizuku.pingBinder()) return false
        return runCatching {
            val output = runShizuku("am task remove $taskId >/dev/null 2>&1; echo \\$?").trim()
            val ok = output.lineSequence().lastOrNull()?.trim() == "0"
            if (!ok) Log.w(TAG, "removeTask($taskId) failed: $output")
            ok
        }.getOrElse {
            Log.w(TAG, "removeTask($taskId) failed", it)
            false
        }
    }

    fun focusTask(taskId: Int): Boolean {
        if (taskId <= 0 || !Shizuku.pingBinder()) return false
        return runCatching {
            val focus = runShizuku("am task focus $taskId >/dev/null 2>&1; echo \\$?").trim()
            if (focus.lineSequence().lastOrNull()?.trim() == "0") return@runCatching true
            val move = runShizuku("am task move-to-front $taskId >/dev/null 2>&1; echo \\$?").trim()
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
            for (task in tasks) if (!order.containsKey(task.taskId)) order[task.taskId] = nextOrder++
            return tasks.sortedBy { order[it.taskId] ?: Long.MAX_VALUE }
        }
    }

    private data class ParsedTask(
        val taskId: Int,
        val displayId: Int,
        val packageName: String,
        val bounds: Rect,
        val topResumed: Boolean,
    )

    private fun parseDump(dump: String): List<ParsedTask> {
        val result = mutableListOf<ParsedTask>()
        var currentDisplay = -1
        var currentTaskId: Int? = null
        var currentPackage: String? = null
        var currentBounds: Rect? = null
        var currentTop = false

        fun flush() {
            val id = currentTaskId
            val pkg = currentPackage
            if (id != null && currentDisplay >= 0 && !pkg.isNullOrBlank()) {
                result += ParsedTask(id, currentDisplay, pkg, currentBounds ?: Rect(), currentTop)
            }
            currentTaskId = null
            currentPackage = null
            currentBounds = null
            currentTop = false
        }

        for (line in dump.lineSequence()) {
            val dm = displayPattern.matcher(line)
            if (dm.find()) {
                flush()
                currentDisplay = dm.group(1).toIntOrNull() ?: -1
                continue
            }

            // A new unindented section ends the current display block.
            if (currentDisplay >= 0 && line.isNotEmpty() && !line[0].isWhitespace()) {
                flush()
                currentDisplay = -1
                continue
            }
            if (currentDisplay < 0) continue

            var foundTaskId: Int? = null
            for (pattern in taskIdPatterns) {
                val m = pattern.matcher(line)
                if (m.find()) {
                    foundTaskId = m.group(1).toIntOrNull()
                    if (foundTaskId != null) break
                }
            }
            if (foundTaskId != null) {
                val isRootTask = line.startsWith("  * Task{") || line.matches(Regex("\\s*Task id #\\d+.*"))
                if (!isRootTask) continue
                if (currentTaskId != foundTaskId) flush()
                currentTaskId = foundTaskId
                currentTop = false
                if (currentPackage == null) {
                    val headerPkg = Pattern.compile("(?:^|\\s)A=\\d+:([^\\s]+)").matcher(line)
                    if (headerPkg.find()) currentPackage = headerPkg.group(1)
                }
            }
            if (currentTaskId == null) continue

            if (line.contains("topResumedActivity=")) {
                currentTop = true
                if (currentPackage == null) currentPackage = extractPackage(line)
            }
            if (currentBounds == null) {
                val b = boundsPattern.matcher(line)
                if (b.find()) currentBounds = Rect(
                    b.group(1).toInt(), b.group(2).toInt(), b.group(3).toInt(), b.group(4).toInt()
                )
            }
            if (currentPackage == null) currentPackage = extractPackage(line)
        }
        flush()
        return result.distinctBy { it.displayId to it.taskId }
    }

    private fun parseTasks(ownPackage: String, parsed: List<ParsedTask>): List<TaskInfo> =
        parsed.filter { it.packageName != ownPackage }.map {
            TaskInfo(it.taskId, it.packageName, it.displayId, it.bounds, it.topResumed)
        }

    private fun extractPackage(line: String): String? {
        realActivityPattern.matcher(line).let { if (it.find()) return it.group(1) }
        activityRecordPattern.matcher(line).let { if (it.find()) return it.group(1) }
        packageNamePattern.matcher(line).let { if (it.find()) return it.group(1) }
        return null
    }

    private fun runShizuku(command: String): String {
        val method: Method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        method.isAccessible = true
        val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        return try {
            // Task discovery must never block the desktop/video lifecycle.
            // Some Lineage/Shizuku builds can keep shell stdout open for longer
            // than expected; bound the read and tear down the helper process.
            val future = FutureTask<String> {
                BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            }
            Thread(future, "vd-task-dump").apply { isDaemon = true }.start()
            runCatching { future.get(1200, java.util.concurrent.TimeUnit.MILLISECONDS) }
                .getOrElse {
                    runCatching { process.destroy() }
                    ""
                }
        } finally {
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.destroy() }
        }
    }

    private fun quoteArg(value: String): String = value.replace("'", "'\\''")
}
