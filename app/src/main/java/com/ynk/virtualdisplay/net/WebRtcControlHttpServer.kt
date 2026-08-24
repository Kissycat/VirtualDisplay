package com.ynk.virtualdisplay.net

import android.util.Log
import com.ynk.virtualdisplay.data.repository.IDisplayRepository
import com.ynk.virtualdisplay.video.output.WebRtcH264OutputStatus
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small dependency-free HTTP control plane for WebRTC/H.264 output.
 *
 * It deliberately stays separate from the H.264 data socket. Default ports:
 *   18080 = VDH1 H.264 data stream
 *   18081 = HTTP control + a browser helper page
 *
 * Routes:
 *   GET  /api/webrtc/status
 *   POST /api/webrtc/start   body: {"displayId":3,"port":18080,"bindHost":"0.0.0.0"}
 *   POST /api/webrtc/stop
 *   GET  /webrtc
 */
class WebRtcControlHttpServer(
    private val repositoryProvider: () -> IDisplayRepository?,
    private val bindHost: String = "0.0.0.0",
    private val port: Int = DEFAULT_PORT
) : AutoCloseable {
    companion object {
        private const val TAG = "WebRtcControlHttpServer"
        const val DEFAULT_PORT = 18081
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_BODY_BYTES = 8 * 1024
        private const val SO_TIMEOUT_MS = 5000
    }

    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "webrtc-http-worker").apply { isDaemon = true }
    }
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null

    @Synchronized
    fun start() {
        if (running.get()) return
        val server = ServerSocket(port, 16, InetAddress.getByName(bindHost)).apply {
            reuseAddress = true
        }
        serverSocket = server
        running.set(true)
        acceptThread = Thread({ acceptLoop(server) }, "webrtc-http-$port").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "WebRTC control API listening on $bindHost:$port")
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            try {
                val socket = server.accept()
                socket.soTimeout = SO_TIMEOUT_MS
                executor.execute { handle(socket) }
            } catch (_: SocketException) {
                if (running.get()) Log.w(TAG, "HTTP accept loop socket error")
            } catch (t: Throwable) {
                if (running.get()) Log.e(TAG, "HTTP accept loop failed", t)
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                val input = BufferedInputStream(s.getInputStream())
                val output = BufferedOutputStream(s.getOutputStream())
                val requestLine = readLine(input, MAX_HEADER_BYTES) ?: return
                val parts = requestLine.split(' ', limit = 3)
                if (parts.size < 2) {
                    writeResponse(output, 400, "application/json; charset=utf-8", "{\"error\":\"bad request\"}")
                    return
                }
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore('?')
                val contentLength = readHeaders(input)
                val body = if (contentLength > 0) readBody(input, contentLength) else ""

                when {
                    method == "OPTIONS" -> writeResponse(output, 204, "text/plain", "")
                    method == "GET" && path == "/api/webrtc/status" -> handleStatus(output)
                    method == "POST" && path == "/api/webrtc/start" -> handleStart(output, body)
                    method == "POST" && path == "/api/webrtc/stop" -> handleStop(output)
                    method == "GET" && path == "/webrtc" -> handleBrowserPage(output)
                    method == "GET" && path == "/" -> redirect(output, "/webrtc")
                    else -> writeResponse(output, 404, "application/json; charset=utf-8", "{\"error\":\"not found\"}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "HTTP request failed", t)
                runCatching { writeResponse(s.getOutputStream().let(::BufferedOutputStream), 500, "application/json; charset=utf-8", "{\"error\":\"internal error\"}") }
            }
        }
    }

    private fun readHeaders(input: BufferedInputStream): Int {
        var contentLength = 0
        var bytes = 0
        while (true) {
            val line = readLine(input, MAX_HEADER_BYTES) ?: break
            bytes += line.toByteArray(StandardCharsets.US_ASCII).size + 2
            if (bytes > MAX_HEADER_BYTES) throw IllegalArgumentException("headers too large")
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
                contentLength = line.substring(colon + 1).trim().toIntOrNull() ?: 0
            }
        }
        require(contentLength <= MAX_BODY_BYTES) { "request body too large" }
        return contentLength
    }

    private fun readBody(input: BufferedInputStream, length: Int): String {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(bytes, offset, length - offset)
            if (n < 0) break
            offset += n
        }
        return String(bytes, 0, offset, StandardCharsets.UTF_8)
    }

    private fun readLine(input: BufferedInputStream, limit: Int): String? {
        val out = ByteArrayOutputStream()
        while (out.size() <= limit) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(StandardCharsets.US_ASCII.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        if (out.size() > limit) throw IllegalArgumentException("line too long")
        return out.toString(StandardCharsets.US_ASCII.name())
    }

    private fun handleStatus(output: BufferedOutputStream) {
        val status = currentStatus()
        writeResponse(output, 200, "application/json; charset=utf-8", statusJson(status))
    }

    private fun handleStart(output: BufferedOutputStream, body: String) {
        val repository = repositoryProvider()
        if (repository == null) {
            writeResponse(output, 503, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"no active repository\"}")
            return
        }
        val displayId = findInt(body, "displayId")
        if (displayId == null) {
            writeResponse(output, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"displayId is required\"}")
            return
        }
        val portValue = findInt(body, "port") ?: 18080
        val host = findString(body, "bindHost") ?: "0.0.0.0"
        val result = repository.startWebRtcH264Output(displayId, host, portValue)
        result.fold(
            onSuccess = { writeResponse(output, 200, "application/json; charset=utf-8", "{\"ok\":true,\"status\":${statusJson(currentStatus())}}") },
            onFailure = { writeResponse(output, 409, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"${jsonEscape(it.message ?: "start failed")}\"}") }
        )
    }

    private fun handleStop(output: BufferedOutputStream) {
        val repository = repositoryProvider()
        if (repository == null) {
            writeResponse(output, 503, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"no active repository\"}")
            return
        }
        repository.stopWebRtcH264Output()
        writeResponse(output, 200, "application/json; charset=utf-8", "{\"ok\":true,\"status\":${statusJson(currentStatus())}}")
    }

    private fun handleBrowserPage(output: BufferedOutputStream) {
        val html = """
            <!doctype html>
            <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>VirtualDisplay WebRTC</title>
            <style>html,body{margin:0;height:100%;background:#111;color:#ddd;font:14px sans-serif}body{display:flex;flex-direction:column}header{padding:10px;background:#181818}main{flex:1;display:flex;align-items:center;justify-content:center}video{width:100%;height:100%;object-fit:contain;background:#000}button,input{font:inherit}#msg{margin-left:12px}</style></head>
            <body><header><button id="start">Start WebRTC</button><button id="stop" disabled>Stop</button><span id="msg">This page is a browser client for a configured WebRTC gateway.</span></header>
            <main><video id="video" autoplay playsinline muted></video></main>
            <script>
            const q=new URLSearchParams(location.search); const gateway=(q.get('gateway')||location.origin).replace(/\\/$/,'');
            const v=document.getElementById('video'),msg=document.getElementById('msg'),start=document.getElementById('start'),stop=document.getElementById('stop'); let pc;
            async function waitIce(pc){if(pc.iceGatheringState==='complete')return;await new Promise(r=>pc.addEventListener('icegatheringstatechange',()=>pc.iceGatheringState==='complete'&&r(),{once:true}));}
            start.onclick=async()=>{try{msg.textContent='Negotiating with '+gateway+' ...';pc=new RTCPeerConnection({iceServers:[]});pc.addTransceiver('video',{direction:'recvonly'});pc.ontrack=e=>v.srcObject=e.streams[0];pc.onconnectionstatechange=()=>msg.textContent='WebRTC: '+pc.connectionState;const offer=await pc.createOffer();await pc.setLocalDescription(offer);await waitIce(pc);const r=await fetch(gateway+'/offer',{method:'POST',headers:{'Content-Type':'application/sdp'},body:pc.localDescription.sdp});if(!r.ok)throw new Error(await r.text());const sdp=await r.text();await pc.setRemoteDescription({type:'answer',sdp});start.disabled=true;stop.disabled=false;}catch(e){msg.textContent='Error: '+e;console.error(e);pc?.close();pc=null;}};
            stop.onclick=()=>{pc?.close();pc=null;v.srcObject=null;start.disabled=false;stop.disabled=true;msg.textContent='Stopped';};
            </script></body></html>
        """.trimIndent()
        writeResponse(output, 200, "text/html; charset=utf-8", html)
    }

    private fun currentStatus(): WebRtcH264OutputStatus = repositoryProvider()?.let { repo ->
        repo.getWebRtcH264OutputStatus()
    } ?: WebRtcH264OutputStatus(false, -1, null, null, 0)

    private fun statusJson(s: WebRtcH264OutputStatus): String {
        val hostJson = s.bindHost?.let { "\"${jsonEscape(it)}\"" } ?: "null"
        val portJson = s.port?.toString() ?: "null"
        return "{\"running\":${s.running},\"displayId\":${s.displayId},\"bindHost\":$hostJson,\"port\":$portJson,\"clientCount\":${s.clientCount}}"
    }

    private fun findInt(body: String, key: String): Int? = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*(-?\\d+)").find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()
    private fun findString(body: String, key: String): String? = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").find(body)?.groupValues?.getOrNull(1)
    private fun jsonEscape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    private fun redirect(output: BufferedOutputStream, location: String) {
        val response = "HTTP/1.1 302 Found\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        output.write(response.toByteArray(StandardCharsets.US_ASCII)); output.flush()
    }

    private fun writeResponse(output: BufferedOutputStream, code: Int, contentType: String, body: String) {
        val payload = body.toByteArray(StandardCharsets.UTF_8)
        val phrase = when (code) { 200 -> "OK"; 204 -> "No Content"; 302 -> "Found"; 400 -> "Bad Request"; 404 -> "Not Found"; 409 -> "Conflict"; 500 -> "Internal Server Error"; 503 -> "Service Unavailable"; else -> "OK" }
        val header = "HTTP/1.1 $code $phrase\r\nContent-Type: $contentType\r\nContent-Length: ${payload.size}\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET,POST,OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type\r\nConnection: close\r\n\r\n"
        output.write(header.toByteArray(StandardCharsets.US_ASCII)); output.write(payload); output.flush()
    }

    @Synchronized
    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        executor.shutdownNow()
        Log.i(TAG, "WebRTC control API stopped")
    }
}
