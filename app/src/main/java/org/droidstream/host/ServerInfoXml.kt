package org.droidstream.host

object ServerInfoXml {

    const val NOT_FOUND = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<root status_code=\"404\"/>"

    // Campi e ordine: Sunshine nvhttp.cpp:1260-1303
    fun build(ctx: Context, localIp: String, pairStatus: Int): String {
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
        sb.append("<PairStatus>").append(pairStatus).append("</PairStatus>")
        sb.append("<currentgame>0</currentgame>")
        sb.append("<state>SUNSHINE_SERVER_FREE</state>")
        sb.append("</root>")
        return sb.toString()
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
