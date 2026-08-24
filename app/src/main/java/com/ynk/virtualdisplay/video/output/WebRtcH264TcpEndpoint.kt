package com.ynk.virtualdisplay.video.output

import android.util.Log
import com.ynk.virtualdisplay.video.EncodedVideoSink
import com.ynk.virtualdisplay.video.VideoStreamController
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional low-overhead H.264 output for a WebRTC gateway.
 *
 * This is intentionally NOT a browser-facing WebRTC signaling server. It exposes
 * the already-encoded H.264 stream without decoding/re-encoding. A Pion/mediasoup/
 * native WebRTC gateway can consume this stream, packetize H.264 into RTP, and
 * publish it through WebRTC/WHIP/WHEP without touching the Android decoder path.
 *
 * Wire format:
 *   connection header: magic[4] = "VDH1", version(int), displayId(int), width(int), height(int)
 *   format update:      type(byte=1), width(int), height(int)
 *   frame packet:       type(byte=2), ptsUs(long), flags(int), size(int), payload[size]
 *
 * Frame flags: bit 0 = codec-config, bit 1 = key-frame.
 *
 * Backpressure is isolated per client. A slow client drops older frame packets and
 * never blocks the MediaCodec input thread.
 */
class WebRtcH264TcpEndpoint(
    private val videoController: VideoStreamController,
    private val displayId: Int,
    private val bindHost: String = "127.0.0.1",
    private val port: Int,
    private val clientQueueCapacity: Int = DEFAULT_QUEUE_CAPACITY
) : EncodedVideoSink, AutoCloseable {

    companion object {
        private const val TAG = "WebRtcH264TcpEndpoint"
        private val MAGIC = "VDH1".toByteArray(Charsets.US_ASCII)
        private const val VERSION = 1
        private const val TYPE_FORMAT = 1
        private const val TYPE_FRAME = 2
        private const val DEFAULT_QUEUE_CAPACITY = 4
        private const val SOCKET_BACKLOG = 8
        private const val ACCEPT_READ_TIMEOUT_MS = 1000
    }

    private data class FramePacket(val bytes: ByteArray)

    private class Client(
        private val socket: Socket,
        queueCapacity: Int,
        private val currentWidth: () -> Int,
        private val currentHeight: () -> Int,
        private val displayId: Int,
    ) : AutoCloseable {
        private val queue = LinkedBlockingDeque<FramePacket>(queueCapacity)
        private val closed = AtomicBoolean(false)
        private val writer = Thread({ runWriter() }, "webrtc-h264-writer-${socket.port}")

        init {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.sendBufferSize = 256 * 1024
        }

        fun start() {
            writer.start()
        }

        fun offer(packet: FramePacket) {
            if (closed.get()) return
            if (!queue.offerLast(packet)) {
                queue.pollFirst()
                queue.offerLast(packet)
            }
        }

        fun offerFormat(width: Int, height: Int) {
            if (closed.get()) return
            val out = java.io.ByteArrayOutputStream(9)
            DataOutputStream(out).use { data ->
                data.writeByte(TYPE_FORMAT)
                data.writeInt(width)
                data.writeInt(height)
            }
            offer(FramePacket(out.toByteArray()))
        }

        private fun runWriter() {
            try {
                val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
                val data = DataOutputStream(out)
                data.write(MAGIC)
                data.writeInt(VERSION)
                data.writeInt(displayId)
                data.writeInt(currentWidth())
                data.writeInt(currentHeight())
                data.flush()

                while (!closed.get()) {
                    val packet = queue.takeFirst()
                    data.write(packet.bytes)
                    data.flush()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: IOException) {
                // Normal when the remote WebRTC gateway disconnects.
            } finally {
                close()
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            queue.clear()
            writer.interrupt()
            runCatching { socket.close() }
        }
    }


    private val running = AtomicBoolean(false)
    private val clients = CopyOnWriteArraySet<Client>()
    private val serverLock = Any()
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var width = 0
    @Volatile private var height = 0

    fun start() {
        synchronized(serverLock) {
            if (running.get()) return
            require(port in 1..65535) { "Invalid endpoint port: $port" }
            require(clientQueueCapacity >= 2) { "clientQueueCapacity must be >= 2" }

            val server = ServerSocket(port, SOCKET_BACKLOG, InetAddress.getByName(bindHost)).apply {
                soTimeout = ACCEPT_READ_TIMEOUT_MS
                reuseAddress = true
            }
            serverSocket = server
            running.set(true)
            videoController.addEncodedVideoSink(this)
            acceptThread = Thread({ acceptLoop(server) }, "webrtc-h264-accept-$port").also { it.start() }
            Log.i(TAG, "H.264 WebRTC bridge endpoint listening on $bindHost:$port for display=$displayId")
        }
    }

    override fun onFormat(codec: String, width: Int, height: Int) {
        if (!codec.equals("H.264", ignoreCase = true)) return
        this.width = width
        this.height = height
        clients.forEach { it.offerFormat(width, height) }
    }

    override fun onFrame(ptsUs: Long, isConfig: Boolean, isKeyFrame: Boolean, data: ByteArray, size: Int) {
        if (!running.get() || clients.isEmpty() || size <= 0) return

        // One copy per encoded frame, shared by all connected clients. The normal
        // decoder path remains zero-copy; this branch only runs while the external
        // endpoint is actually being consumed.
        val stream = java.io.ByteArrayOutputStream(1 + 8 + 4 + 4 + size)
        val out = DataOutputStream(stream)
        out.writeByte(TYPE_FRAME)
        out.writeLong(ptsUs)
        var flags = 0
        if (isConfig) flags = flags or 1
        if (isKeyFrame) flags = flags or 2
        out.writeInt(flags)
        out.writeInt(size)
        out.write(data, 0, size)
        out.flush()
        val shared = FramePacket(stream.toByteArray())
        clients.forEach { it.offer(shared) }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            try {
                val socket = server.accept()
                val client = Client(socket, clientQueueCapacity, { width }, { height }, displayId)
                clients.add(client)
                try {
                    client.start()
                    Log.i(TAG, "H.264 WebRTC bridge client connected from ${socket.inetAddress.hostAddress}:${socket.port}")
                } catch (e: IOException) {
                    clients.remove(client)
                    client.close()
                }
            } catch (_: java.net.SocketTimeoutException) {
                // Periodically check running flag.
            } catch (_: SocketException) {
                break
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "Accept loop failed", e)
            }
        }
    }


    fun status(): WebRtcH264OutputStatus = WebRtcH264OutputStatus(
        running = running.get(),
        displayId = displayId,
        bindHost = bindHost.takeIf { running.get() },
        port = port.takeIf { running.get() },
        clientCount = clients.size
    )

    override fun close() {
        synchronized(serverLock) {
            if (!running.compareAndSet(true, false)) return
            videoController.removeEncodedVideoSink(this)
            runCatching { serverSocket?.close() }
            serverSocket = null
            clients.forEach { it.close() }
            clients.clear()
            acceptThread?.interrupt()
            acceptThread = null
            Log.i(TAG, "H.264 WebRTC bridge endpoint stopped")
        }
    }
}
