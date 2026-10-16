package com.ghostwan.ageofandroid.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A two-way message pipe between two devices. Messages are opaque byte arrays (see [Msg]).
 * The game only polls it from its main loop, so implementations do their I/O on their own threads.
 *
 * Today: [TcpLink] on the local network. An Internet relay only needs another implementation
 * (e.g. WebSocket binary frames carry the same messages); sessions don't change.
 */
interface Link {
    /** Queues [msg] for sending; never blocks. */
    fun send(msg: ByteArray)

    /** Next received message, or null if none is waiting. */
    fun poll(): ByteArray?

    val isOpen: Boolean

    fun close()
}

/**
 * [Link] over a TCP socket. Frames are a 4-byte length then the message; an empty frame is a
 * heartbeat, sent when the line is idle, so a vanished peer is detected within [TIMEOUT_MS].
 */
class TcpLink(private val socket: Socket) : Link {
    private val inbox = ConcurrentLinkedQueue<ByteArray>()
    private val outbox = LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var open = true

    override val isOpen get() = open

    init {
        socket.tcpNoDelay = true
        socket.soTimeout = TIMEOUT_MS
        Thread({ readLoop() }, "net-read").apply { isDaemon = true }.start()
        Thread({ writeLoop() }, "net-write").apply { isDaemon = true }.start()
    }

    override fun send(msg: ByteArray) {
        if (open) outbox.put(msg)
    }

    override fun poll(): ByteArray? = inbox.poll()

    /** Messages queued before closing are still sent; the writer then closes the socket. */
    override fun close() {
        if (!open) return
        open = false
        outbox.put(CLOSE)
    }

    private fun readLoop() {
        try {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            while (open) {
                val len = input.readInt()
                if (len < 0 || len > MAX_MESSAGE) throw IOException("Bad frame length $len")
                if (len == 0) continue // heartbeat
                val msg = ByteArray(len)
                input.readFully(msg)
                inbox += msg
            }
        } catch (_: SocketTimeoutException) {
        } catch (_: IOException) {
        } finally {
            close()
        }
    }

    private fun writeLoop() {
        try {
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            while (true) {
                val msg = outbox.poll(HEARTBEAT_MS, TimeUnit.MILLISECONDS)
                if (msg === CLOSE) break
                if (msg == null) {
                    if (!open) break
                    output.writeInt(0)
                } else {
                    output.writeInt(msg.size)
                    output.write(msg)
                }
                // Batch whatever is already queued, then flush.
                if (outbox.isEmpty()) output.flush()
            }
            output.flush()
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        } finally {
            open = false
            try {
                socket.close()
            } catch (_: IOException) {
            }
        }
    }

    companion object {
        private val CLOSE = ByteArray(0)
        const val TIMEOUT_MS = 12_000
        const val HEARTBEAT_MS = 2_000L
        const val MAX_MESSAGE = 16 * 1024 * 1024

        /** Connects to a host; blocking, call it off the main thread. */
        fun connect(host: String, port: Int, timeoutMs: Int = 5_000): TcpLink {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            return TcpLink(socket)
        }
    }
}

/** Accepts guests on the host. */
class TcpListener(port: Int) {
    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(port))
    }
    private val accepted = ConcurrentLinkedQueue<Link>()

    /** Actual port (useful when created with port 0). */
    val port: Int = server.localPort

    init {
        Thread({
            try {
                while (!server.isClosed) accepted += TcpLink(server.accept())
            } catch (_: IOException) {
            }
        }, "net-accept").apply { isDaemon = true }.start()
    }

    fun poll(): Link? = accepted.poll()

    fun close() {
        try {
            server.close()
        } catch (_: IOException) {
        }
        while (true) (accepted.poll() ?: break).close()
    }
}
