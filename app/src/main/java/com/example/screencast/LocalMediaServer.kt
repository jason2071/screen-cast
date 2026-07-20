package com.example.screencast

import android.content.ContentResolver
import android.net.Uri
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Serves a single content:// video over HTTP so a Chromecast can pull it from the phone.
 *
 * Cast receivers fetch media themselves over the network, so a content URI or a local file path
 * is useless to them. Publishing the file on the phone's Wi-Fi address is the standard workaround.
 * Range requests are supported so the receiver can seek.
 */
class LocalMediaServer(private val resolver: ContentResolver) : Closeable {

    private class Item(val path: String, val uri: Uri, val mimeType: String, val length: Long)

    private val workers = Executors.newCachedThreadPool()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var item: Item? = null

    /** Publishes [uri] and returns the URL a receiver on the same Wi-Fi can fetch, or null. */
    fun publish(uri: Uri): String? {
        val length = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: return null
        if (length <= 0L) return null

        val socket = serverSocket ?: startServer().also { serverSocket = it }
        val address = wifiAddress() ?: return null

        val path = "/" + UUID.randomUUID().toString().replace("-", "")
        item = Item(path, uri, resolver.getType(uri) ?: "video/*", length)
        return "http://$address:${socket.localPort}$path"
    }

    private fun startServer(): ServerSocket {
        // Port 0 lets the OS pick a free port.
        val socket = ServerSocket(0)
        acceptThread = Thread {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                workers.execute { handle(client) }
            }
        }.apply { isDaemon = true; start() }
        return socket
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            val input = socket.getInputStream().bufferedReader()
            val requestLine = input.readLine() ?: return
            var rangeHeader: String? = null
            while (true) {
                val line = input.readLine()
                if (line.isNullOrEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substringAfter(':').trim()
                }
            }

            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0).orEmpty()
            val path = parts.getOrNull(1).orEmpty()
            val served = item

            val output = socket.getOutputStream()
            if (served == null || path != served.path) {
                output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                output.flush()
                return
            }

            val (start, end) = parseRange(rangeHeader, served.length)
            val count = end - start + 1
            val status = if (rangeHeader == null) "200 OK" else "206 Partial Content"

            val headers = buildString {
                append("HTTP/1.1 $status\r\n")
                append("Content-Type: ${served.mimeType}\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $count\r\n")
                if (rangeHeader != null) {
                    append("Content-Range: bytes $start-$end/${served.length}\r\n")
                }
                append("Connection: close\r\n\r\n")
            }
            output.write(headers.toByteArray())

            if (method.equals("HEAD", ignoreCase = true)) {
                output.flush()
                return
            }

            try {
                streamBody(served.uri, start, count, output)
            } catch (_: IOException) {
                // Receiver closed the connection (seek or stop) — nothing to recover.
            }
        }
    }

    private fun streamBody(uri: Uri, start: Long, count: Long, output: OutputStream) {
        resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { file ->
                file.channel.position(start)
                val stream = BufferedInputStream(file)
                val buffer = ByteArray(64 * 1024)
                var remaining = count
                while (remaining > 0) {
                    val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
                output.flush()
            }
        }
    }

    private fun parseRange(header: String?, length: Long): Pair<Long, Long> {
        val spec = header?.substringAfter("bytes=", "")?.substringBefore(',')?.trim()
        if (spec.isNullOrEmpty()) return 0L to length - 1

        val start = spec.substringBefore('-').toLongOrNull() ?: 0L
        val end = spec.substringAfter('-').toLongOrNull() ?: (length - 1)
        return start.coerceIn(0L, length - 1) to end.coerceIn(start, length - 1)
    }

    override fun close() {
        item = null
        try {
            serverSocket?.close()
        } catch (_: IOException) {
            // Already closed.
        }
        serverSocket = null
        acceptThread = null
        workers.shutdownNow()
    }

    companion object {
        /** The phone's own address on the local network, or null when not on Wi-Fi. */
        fun wifiAddress(): String? = NetworkInterface.getNetworkInterfaces()
            ?.toList()
            ?.asSequence()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.toList().asSequence() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }
}
