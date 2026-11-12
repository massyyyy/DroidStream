package org.droidstream.host

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface

object ServerInfoXml {

    const val NOT_FOUND = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<root status_code=\"404\"/>"

    fun lanIpv4(): String {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val ni = ifaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) {
                        return a.hostAddress
                    }
                }
            }
        } catch (e: Exception) {}
        return "192.168.1.218"
    }

    fun build(ctx: Context, localIp: String, pairStatus: Int): String {
        val ip = lanIpv4()
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
        sb.append("<LocalIP>").append(esc(ip)).append("</LocalIP>")
        sb.append("<ServerCodecModeSupport>1</ServerCodecModeSupport>")
        sb.append("<PairStatus>").append(pairStatus).append("</PairStatus>")
        sb.append("<currentgame>0</currentgame>")
        sb.append("<state>MJOLNIR_SERVER_FREE</state>")
        sb.append("</root>")
        return sb.toString()
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
