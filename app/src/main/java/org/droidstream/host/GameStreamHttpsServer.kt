package org.droidstream.host

import android.content.Context
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

class GameStreamHttpsServer(private val ctx: Context) {

    private var serverSocket: SSLServerSocket? = null
    private val running = AtomicBoolean(false)

    fun start(port: Int, ssl: SSLContext) {
        if (running.get()) return
        val ss = ssl.serverSocketFactory.createServerSocket() as SSLServerSocket
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        serverSocket = ss
        running.set(true)
        Thread({ acceptLoop(ss) }, "gs-https-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
    }

    private fun acceptLoop(ss: SSLServerSocket) {
        while (running.get()) {
            try {
                val s = ss.accept()
                Thread({ handle(s) }, "gs-https-conn").apply { isDaemon = true }.start()
            } catch (e: Exception) {
                if (running.get()) Logger.i(ctx, "https accept: " + e.javaClass.simpleName + ": " + e.message)
                return
            }
        }
    }

    private fun handle(s: Socket) {
        try {
            s.soTimeout = 8000
            val reader = s.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
            }
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val full = parts[1]
            val path = full.substringBefore('?')
            val query = if (full.contains('?')) full.substringAfter('?') else ""
            Logger.i(ctx, "HTTPS " + requestLine + " da " + s.inetAddress.hostAddress)

            val localIp = s.localAddress.hostAddress ?: "127.0.0.1"
            val status: String
            val body: String
            when (path) {
                "/serverinfo" -> {
                    status = "200 OK"
                    body = serverInfoXml(localIp)
                }
                "/pair" -> {
                    Logger.i(ctx, "HTTPS PAIR RICHIESTO query=" + query)
                    status = "404 Not Found"
                    body = NOT_FOUND
                }
                else -> {
                    status = "404 Not Found"
                    body = NOT_FOUND
                }
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
            Logger.i(ctx, "https conn: " + e.javaClass.simpleName + ": " + e.message)
        } finally {
            try { s.close() } catch (e: Exception) {}
        }
    }

    // Campi copiati da Sunshine nvhttp.cpp:1260-1303.
    // PairStatus=0 e mac finto finche' non esiste il pairing (M2b).
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

    private companion object {
        const val NOT_FOUND = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<root status_code=\"404\"/>"
    }
}
