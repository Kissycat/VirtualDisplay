package com.ynk.virtualdisplay.webrtc

import android.content.Context
import android.os.Build
import android.util.Log
import com.ynk.virtualdisplay.data.AppSettings
import com.ynk.virtualdisplay.data.PrivilegeMode
import rikka.shizuku.Shizuku
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Starts the bundled WebRTC gateway through Shizuku/root from /data/local/tmp.
 *
 * The H.264 endpoint is started directly by the Android app before this gateway
 * is launched. The gateway only consumes that already-running endpoint.
 * The HTTP /api/webrtc/start route remains available for external debugging,
 * but is intentionally not used by the in-app startup path.
 *
 * We never use Process.waitFor()/exitValue() for the long-running gateway.
 * Shizuku Process wrappers are not a reliable lifecycle source for a daemon.
 */
class GatewayProcessController(private val context: Context) : AutoCloseable {
    companion object {
        private const val TAG = "GatewayProcessController"
        private const val BINARY_NAME = "virtualdisplay-webrtc-gateway"
        private const val REMOTE_PATH = "/data/local/tmp/$BINARY_NAME"
        const val DEFAULT_H264_PORT = 18080
        const val DEFAULT_GATEWAY_PORT = 19000
        const val DEFAULT_CONTROL_PORT = 18081
        private const val LOG_PATH = "/data/local/tmp/virtualdisplay-webrtc-gateway.log"
        private const val PID_PATH = "/data/local/tmp/virtualdisplay-webrtc-gateway.pid"
        private const val START_TIMEOUT_MS = 10_000L
        private const val PROBE_TIMEOUT_MS = 300
    }

    @Volatile private var pid: Int = -1

    fun isRunning(): Boolean = isPortOpen(DEFAULT_GATEWAY_PORT)

    /** Starts gateway + H264 stream for the requested virtual display. */
    @Synchronized
    fun start(
        displayId: Int,
        h264Port: Int = DEFAULT_H264_PORT,
        gatewayPort: Int = DEFAULT_GATEWAY_PORT,
        bindHost: String = "0.0.0.0"
    ): Result<String> {
        if (displayId < 0) return Result.failure(IllegalArgumentException("Invalid displayId=$displayId"))

        val mode = AppSettings.getPrivilegeModeSync()
        if (mode == PrivilegeMode.NONE) {
            return Result.failure(IllegalStateException("WebRTC Gateway requires Shizuku or root privilege"))
        }
        if (mode == PrivilegeMode.SHIZUKU &&
            (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED)
        ) {
            return Result.failure(IllegalStateException("Shizuku is not available or not authorized"))
        }

        return runCatching {
            // If an old gateway is already listening, reuse it and simply bind the
            // requested display to the H264 endpoint.
            if (!isPortOpen(gatewayPort)) {
                installBinary()
                launchGateway(h264Port, gatewayPort)
                if (!waitForPort("127.0.0.1", gatewayPort, START_TIMEOUT_MS)) {
                    val log = readGatewayLog().take(4096)
                    throw IOException("Gateway did not open port $gatewayPort${if (log.isBlank()) "" else ": $log"}")
                }
            }

            Log.i(TAG, "WebRTC gateway started; H264 endpoint is managed by the app. displayId=$displayId h264Port=$h264Port gatewayPort=$gatewayPort")
            browserUrl(gatewayPort)
        }
    }

    /** Stops H264 output first, then terminates the gateway process if we own it. */
    @Synchronized
    fun stop() {
        stopH264Output(DEFAULT_CONTROL_PORT)
        stopRemoteGateway()
        pid = -1
        runCatching {
            runPrivilegedShell("rm -f $REMOTE_PATH $LOG_PATH $PID_PATH")
        }
    }

    private fun stopH264Output(controlPort: Int) {
        runCatching {
            val conn = (URL("http://127.0.0.1:$controlPort/api/webrtc/stop").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 1_000
                readTimeout = 2_000
            }
            conn.responseCode
            conn.disconnect()
        }
    }

    private fun installBinary() {
        val abi = selectAbi()
        val assetPath = "gateway/$abi/$BINARY_NAME"
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        if (bytes.isEmpty()) throw IOException("Gateway binary asset is empty: $assetPath")

        val command = "cat > $REMOTE_PATH && chmod 700 $REMOTE_PATH"
        val writer = executePrivileged(arrayOf("sh", "-c", command))
            ?: throw IOException("Failed to create privileged writer")
        try {
            writer.outputStream.use { it.write(bytes); it.flush() }
            val completed = runCatching { writer.waitFor(15, TimeUnit.SECONDS) }.getOrDefault(false)
            if (!completed) {
                writer.destroy()
                throw IOException("Timed out installing gateway binary")
            }
            if (writer.exitValue() != 0) {
                val err = runCatching { writer.errorStream.bufferedReader().readText() }.getOrDefault("")
                throw IOException("Failed to install gateway: ${err.ifBlank { "exit=${writer.exitValue()}" }}")
            }
        } finally {
            runCatching { writer.inputStream.close() }
            runCatching { writer.errorStream.close() }
        }
    }

    private fun launchGateway(h264Port: Int, gatewayPort: Int) {
        val command = buildString {
            append("rm -f '").append(PID_PATH).append("'; ")
            append("export ANDROID_H264='127.0.0.1:").append(h264Port).append("'; ")
            append("export ANDROID_CONTROL='127.0.0.1:").append(DEFAULT_CONTROL_PORT).append("'; ")
            append("export LISTEN='0.0.0.0:").append(gatewayPort).append("'; ")
            append("nohup '").append(REMOTE_PATH).append("' </dev/null >'").append(LOG_PATH).append("' 2>&1 & ")
            append("echo \$! > '").append(PID_PATH).append("'; ")
            append("sleep 0.2; cat '").append(PID_PATH).append("' 2>/dev/null || true")
        }
        val launcher = executePrivileged(arrayOf("sh", "-c", command))
            ?: throw IOException("Failed to launch privileged gateway command")
        try {
            val output = launcher.inputStream.bufferedReader().readText().trim()
            pid = output.lineSequence().mapNotNull { it.trim().toIntOrNull() }.lastOrNull() ?: -1
            // This shell is only a launcher; it is expected to exit quickly. Never
            // use its exit status as proof that the long-lived gateway is alive.
            runCatching { launcher.waitFor(2, TimeUnit.SECONDS) }
            Log.i(TAG, "Gateway launcher output=$output pid=$pid")
        } finally {
            runCatching { launcher.destroy() }
            runCatching { launcher.inputStream.close() }
            runCatching { launcher.errorStream.close() }
        }
    }

    private fun stopRemoteGateway() {
        runCatching {
            val p = pid
            val cmd = if (p > 0) {
                "kill -TERM $p 2>/dev/null || true; sleep 0.2; kill -KILL $p 2>/dev/null || true; rm -f '$PID_PATH'"
            } else {
                "if [ -f '$PID_PATH' ]; then p=\$(cat '$PID_PATH' 2>/dev/null || true); kill -TERM \$p 2>/dev/null || true; sleep 0.2; kill -KILL \$p 2>/dev/null || true; fi; rm -f '$PID_PATH'"
            }
            runPrivilegedShell(cmd)
        }
    }

    private fun runPrivilegedShell(command: String): Boolean {
        val proc = executePrivileged(arrayOf("sh", "-c", command)) ?: return false
        return try {
            runCatching { proc.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)
        } finally {
            runCatching { proc.destroy() }
        }
    }

    private fun readGatewayLog(): String {
        val proc = executePrivileged(arrayOf("sh", "-c", "tail -c 4096 '$LOG_PATH' 2>/dev/null || true")) ?: return ""
        return try {
            val future = java.util.concurrent.FutureTask<String> { proc.inputStream.bufferedReader().readText() }
            Thread(future, "gateway-log-read").apply { isDaemon = true }.start()
            runCatching { future.get(1500, TimeUnit.MILLISECONDS) }.getOrNull().orEmpty()
        } finally {
            runCatching { proc.destroy() }
        }
    }

    private fun jsonEscape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun browserUrl(port: Int): String = "http://127.0.0.1:$port/"

    private fun selectAbi(): String {
        val supported = Build.SUPPORTED_ABIS.map(String::lowercase)
        return when {
            supported.contains("arm64-v8a") -> "arm64-v8a"
            supported.contains("armeabi-v7a") -> "armeabi-v7a"
            supported.contains("x86_64") -> "x86_64"
            supported.contains("x86") -> "x86"
            else -> throw UnsupportedOperationException("Unsupported ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        }
    }

    private fun executePrivileged(cmd: Array<String>): Process? {
        return when (AppSettings.getPrivilegeModeSync()) {
            PrivilegeMode.SHIZUKU -> invokeShizuku(cmd)
            PrivilegeMode.ROOT -> {
                val shell = cmd.joinToString(" ") { quoteShell(it) }
                runCatching { ProcessBuilder("su", "-c", shell).redirectErrorStream(true).start() }.getOrNull()
            }
            PrivilegeMode.NONE -> null
        }
    }

    private fun invokeShizuku(cmd: Array<String>): Process? = runCatching {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        method.isAccessible = true
        method.invoke(null, cmd, null, null) as? Process
    }.getOrElse {
        Log.e(TAG, "Shizuku newProcess failed", it)
        null
    }

    private fun isPortOpen(port: Int, host: String = "127.0.0.1"): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS) }
        true
    } catch (_: Throwable) { false }

    private fun waitForPort(host: String, port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isPortOpen(port, host)) return true
            Thread.sleep(100)
        }
        return false
    }

    override fun close() = Unit

    private fun quoteShell(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
