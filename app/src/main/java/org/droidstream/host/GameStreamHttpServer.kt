package org.droidstream.host

import android.content.Context
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class GameStreamHttpServer(private val ctx: Context) {

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)

    @Volatile var requestCount: Int = 0
    @Volatile var lastRequest: String = "(nessuna richiesta)"

    fun start(port: Int) {
        if (running.get()) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        serverSocket = ss
        running.set(true)
        Thread({ acceptLoop(ss) }, "gs-http-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            try {
                val s = ss.accept()
                Thread({ handle(s) }, "gs-http-conn").apply { isDaemon = true }.start()
            } catch (e: Exception) {
                if (running.get()) Logger.i(ctx, "accept: " + e.message)
                return
            }
        }
    }

    private fun handle(s: Socket) {
        try {
            s.soTimeout = 5000
            val reader = s.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
            }
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val path = parts[1].substringBefore('?')
            requestCount++
            lastRequest = requestLine
            Logger.i(ctx, "HTTP " + requestLine + " da " + s.inetAddress.hostAddress)

            val localIp = s.localAddress.hostAddress ?: "127.0.0.1"
            val body: String
            val status: String
            when (path) {
                "/serverinfo" -> { status = "200 OK"; body = serverInfoXml(localIp) }
                else -> { status = "404 Not Found"; body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<root status_code=\"404\"/>" }
            }
            val bytes = body.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 " + status + "\r\n" +
                    "Content-Type: text/xml; charset=utf-8\r\n" +
                    "Content-Length: " + bytes.size + "\r\n" +
                    "Connection: close\r\n\r\n"
            s.getOutputStream().write(head.toByteArray(Charsets.US_ASCII))
            s.getOutputStream().write(bytes)
            s.getOutputStream().flush()
        } catch (e: Exception) {
            Logger.i(ctx, "conn: " + e.message)
        } finally {
            try { s.close() } catch (e: Exception) {}
        }
    }

    // Campi e ordine copiati da Sunshine nvhttp.cpp:1260-1303
    private fun serverInfoXml(localIp: String): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        sb.append("<root status_code=\"200\">")
        sb.append("<hostname>").append(esc(HostIdentity.hostname(ctx))).append("</hostname>")
        sb.append("<appversion>").append(HostIdentity.APP_VERSION).append("</appversion>")
        sb.append("<GfeVersion>").append(HostIdentity.GFE_VERSION).append("</GfeVersion>")
        sb.append("<uniqueid>").append(HostIdentity.uniqueId(ctx)).append("</uniqueid>")
        sb.append("<HttpsPort>").append(HostIdentity.HTTPS_PORT).append("</HttpsPort>")
        sb.append("<ExternalPort>").append(HostIdentity.HTTP_PORT).append("</ExternalPort>")
        sb.append("<MaxLumaPixelsHEVC>0</MaxLumaPixelsHEVC>")
        sb.append("<mac>00:00:00:00:00:00</mac>")
        sb.append("<LocalIP>").append(esc(localIp)).append("</LocalIP>")
        sb.append("<ServerCodecModeSupport>1</ServerCodecModeSupport>")
        sb.append("<PairStatus>0</PairStatus>")
        sb.append("<currentgame>0</currentgame>")
        sb.append("<state>SUNSHINE_SERVER_FREE</state>")
        sb.append("</root>")
        return sb.toString()
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun selfTest(): String {
        return try {
            val s = Socket()
            s.connect(InetSocketAddress("127.0.0.1", HostIdentity.HTTP_PORT), 3000)
            s.soTimeout = 3000
            s.getOutputStream().write(
                "GET /serverinfo HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII))
            s.getOutputStream().flush()
            val text = s.getInputStream().bufferedReader().readText()
            s.close()
            text
        } catch (e: Exception) {
            "SELF-TEST FALLITO: " + e.message
        }
    }
}
