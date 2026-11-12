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
    private var clientSocket: Socket? = null
    private var clientPort: Int = 0
    
    fun start() {
        if (running.get()) return
        running.set(true)
        Thread({
            try {
                serverSocket = ServerSocket(port)
                serverSocket?.reuseAddress = true
                Log.i(TAG, "RTSP server listening on port $port")
                while (running.get()) {
                    val socket = serverSocket?.accept() ?: break
                    clientSocket = socket
                    handleClient(socket)
                }
            } catch (e: Exception) {
                Log.e(TAG, "RTSP server error: ${e.message}")
            }
        }, "rtsp-server").apply { isDaemon = true }.start()
    }
    
    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (e: Exception) {}
        try { clientSocket?.close() } catch (e: Exception) {}
        serverSocket = null
        clientSocket = null
    }
    
    private fun handleClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)
            
            while (running.get() && !socket.isClosed) {
                val requestLine = reader.readLine() ?: break
                val headers = mutableMapOf<String, String>()
                var contentLength = 0
                var payload = ""
                
                // Parse headers
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val key = line.substring(0, colon).trim()
                        val value = line.substring(colon + 1).trim()
                        headers[key.lowercase()] = value
                        if (key.lowercase() == "content-length") {
                            contentLength = value.toIntOrNull() ?: 0
                        }
                    }
                }
                
                // Parse payload if present
                if (contentLength > 0) {
                    val payloadBuffer = CharArray(contentLength)
                    reader.read(payloadBuffer, 0, contentLength)
                    payload = String(payloadBuffer)
                }
                
                Log.i(TAG, "RTSP request: $requestLine")
                
                // Parse request
                val parts = requestLine.split(" ")
                if (parts.size < 3) continue
                
                val method = parts[0]
                val target = parts[1]
                val cseq = headers["cseq"]?.toIntOrNull() ?: 0
                
                when (method) {
                    "OPTIONS" -> {
                        writer.print("RTSP/1.0 200 OK\r\n")
                        writer.print("CSeq: $cseq\r\n")
                        writer.print("Public: OPTIONS, DESCRIBE, SETUP, ANNOUNCE, PLAY, TEARDOWN\r\n")
                        writer.print("\r\n")
                        writer.flush()
                    }
                    "DESCRIBE" -> {
                        val sdp = buildSdp()
                        writer.print("RTSP/1.0 200 OK\r\n")
                        writer.print("CSeq: $cseq\r\n")
                        writer.print("Content-Type: application/sdp\r\n")
                        writer.print("Content-Length: ${sdp.length}\r\n")
                        writer.print("\r\n")
                        writer.print(sdp)
                        writer.flush()
                    }
                    "SETUP" -> {
                        val transport = headers["transport"] ?: ""
                        val clientPortMatch = Regex("client_port=(\\d+)-(\\d+)").find(transport)
                        if (clientPortMatch != null) {
                            clientPort = clientPortMatch.groupValues[1].toIntOrNull() ?: 0
                        }
                        writer.print("RTSP/1.0 200 OK\r\n")
                        writer.print("CSeq: $cseq\r\n")
                        writer.print("Session: DEADBEEFCAFE;timeout=90\r\n")
                        writer.print("Transport: RTP/AVP/UDP;unicast;server_port=47998\r\n")
                        writer.print("\r\n")
                        writer.flush()
                    }
                    "ANNOUNCE" -> {
                        Log.i(TAG, "ANNOUNCE payload: $payload")
                        writer.print("RTSP/1.0 200 OK\r\n")
                        writer.print("CSeq: $cseq\r\n")
                        writer.print("\r\n")
                        writer.flush()
                    }
                    "PLAY" -> {
                        writer.print("RTSP/1.0 200 OK\r\n")
                        writer.print("CSeq: $cseq\r\n")
                        writer.print("Session: DEADBEEFCAFE;timeout=90\r\n")
                        writer.print("\r\n")
                        writer.flush()
                        Log.i(TAG, "PLAY received, starting video stream")
                        // Signal to start streaming
                        LaunchHandler.onPlayReceived()
                    }
                    "TEARDOWN" -> {
                        writer.print("RTSP/1.0 200 OK\r\n")
                        writer.print("CSeq: $cseq\r\n")
                        writer.print("\r\n")
                        writer.flush()
                        LaunchHandler.stopStreaming()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client handling error: ${e.message}")
        }
    }
    
    private fun buildSdp(): String {
        return """v=0
o=- 0 0 IN IP4 0.0.0.0
s=Moonlight
c=IN IP4 0.0.0.0
t=0 0
a=x-ss-general.featureFlags:135
a=x-ss-general.encryptionSupported:1
a=x-ss-general.encryptionRequested:0
a=x-nv-video[0].refPicInvalidation:1
sprop-parameter-sets=AAAAAU
a=rtpmap:97 H264/90000
a=fmtp:97 packetization-mode=1; profile-level-id=42001f
a=rtpmap:98 AV1/90000
a=fmtp:98 surround-params=21101234567
a=fmtp:97 surround-params=21101234567
"""
    }
}
