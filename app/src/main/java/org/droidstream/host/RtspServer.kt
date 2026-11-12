package org.droidstream.host

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class RtspServer(private val port: Int = 48010) {
    private val TAG = "RtspServer"
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)

    fun start() {
        if (running.get()) return
        running.set(true)
        Thread({
            try {
                serverSocket = ServerSocket(port)
                serverSocket?.reuseAddress = true
                Log.i(TAG, "RTSP server listening on port $port")
                while (running.get()) {
                    val s = serverSocket?.accept() ?: break
                    // UNA CONNESSIONE PER THREAD: non blocchiamo l'accept
                    Thread({ handleClient(s) }, "rtsp-conn").apply { isDaemon = true }.start()
                }
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "RTSP server error: ${e.message}")
            }
        }, "rtsp-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
    }

    // Gestisce UN comando e poi CHIUDE la connessione (come Sunshine).
    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 10000
            Log.i(TAG, "RTSP connection from ${socket.inetAddress}")
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)

            val requestLine = reader.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val k = line.substring(0, colon).trim().lowercase()
                    val v = line.substring(colon + 1).trim()
                    headers[k] = v
                    if (k == "content-length") contentLength = v.toIntOrNull() ?: 0
                }
            }
            var payload = ""
            if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                payload = String(buf, 0, read)
            }

            Log.i(TAG, "RTSP request: $requestLine")
            val parts = requestLine.split(" ")
            if (parts.size < 3) return
            val method = parts[0]
            val target = parts[1]
            val cseq = headers["cseq"]?.toIntOrNull() ?: 0

            when (method) {
                "OPTIONS" -> respond(writer, cseq,
                    "Public: OPTIONS, DESCRIBE, SETUP, ANNOUNCE, PLAY, TEARDOWN\r\n")
                "DESCRIBE" -> {
                    val sdp = buildSdp()
                    writer.print("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n" +
                        "Content-Type: application/sdp\r\nContent-Length: ${sdp.length}\r\n\r\n$sdp")
                    writer.flush()
                }
                "SETUP" -> {
                    val type = when {
                        target.contains("video") -> "video"
                        target.contains("audio") -> "audio"
                        target.contains("control") -> "control"
                        else -> "video"
                    }
                    val serverPort = when (type) {
                        "audio" -> 48000
                        "control" -> 47999
                        else -> 47998
                    }
                    val extra = if (type == "control")
                        "X-SS-Connect-Data: 1234567890\r\n"
                    else
                        "X-SS-Ping-Payload: 0000000000000000\r\n"
                    respond(writer, cseq,
                        "Session: DEADBEEFCAFE;timeout = 90\r\n" +
                        "Transport: server_port=$serverPort\r\n$extra")
                }
                "ANNOUNCE" -> {
                    Log.i(TAG, "ANNOUNCE payload:\n$payload")
                    respond(writer, cseq, "")
                }
                "PLAY" -> {
                    respond(writer, cseq, "Session: DEADBEEFCAFE;timeout = 90\r\n")
                    Log.i(TAG, "PLAY received - starting video stream")
                    LaunchHandler.screenCapture?.startSending()
                }
                "TEARDOWN" -> {
                    respond(writer, cseq, "")
                    LaunchHandler.stop()
                }
                else -> respond(writer, cseq, "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "RTSP client error: ${e.message}")
        } finally {
            // CHIAVE: chiudiamo dopo ogni comando, come Sunshine
            try { socket.close() } catch (e: Exception) {}
        }
    }

    private fun respond(writer: PrintWriter, cseq: Int, extraHeaders: String) {
        writer.print("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n$extraHeaders\r\n")
        writer.flush()
    }

    private fun buildSdp(): String {
        return "v=0\r\n" +
            "o=- 0 0 IN IP4 0.0.0.0\r\n" +
            "s=Moonlight\r\n" +
            "c=IN IP4 0.0.0.0\r\n" +
            "t=0 0\r\n" +
            "a=x-ss-general.featureFlags:135\r\n" +
            "a=x-ss-general.encryptionSupported:1\r\n" +
            "a=x-ss-general.encryptionRequested:0\r\n" +
            "a=x-nv-video[0].refPicInvalidation:1\r\n" +
            "sprop-parameter-sets=AAAAAU\r\n" +
            "a=rtpmap:97 H264/90000\r\n" +
            "a=fmtp:97 packetization-mode=1; profile-level-id=42001f\r\n" +
            "a=fmtp:97 surround-params=211012\r\n"
    }
}
