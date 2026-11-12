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
                if (running.get()) Logger.i(ctx, "https accept: " + e.message)
                return
            }
        }
    }

    private fun handle(s: Socket) {
        try {
            s.soTimeout = 130000
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
            val query = Pairing.parseQuery(if (full.contains('?')) full.substringAfter('?') else "")
            Logger.i(ctx, "HTTPS " + requestLine.take(400) + " da " + s.inetAddress.hostAddress)

            val localIp = s.localAddress.hostAddress ?: "127.0.0.1"
            val res: Pair<Int, String> = when (path) {
                "/serverinfo" -> Pairing.serverInfoXml(ctx, localIp, true, query["uniqueid"]) { ps ->
                    ServerInfoXml.build(ctx, localIp, ps)
                }
                "/pair" -> Pairing.handlePair(ctx, query, true)
                "/applist" -> Pairing.applistXml()
                else -> Pair(404, ServerInfoXml.NOT_FOUND)
            }
            val bytes = res.second.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 " + res.first + " " + (if (res.first == 200) "OK" else "Error") + "\r\n" +
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
}
