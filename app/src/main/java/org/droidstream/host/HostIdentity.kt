package org.droidstream.host

import android.content.Context
import android.os.Build
import java.util.UUID

object HostIdentity {
    // Verificati: Limelight.h:911-917, MoonBridge.java:58-64
    const val HTTP_PORT = 47989
    const val HTTPS_PORT = 47984
    const val RTSP_PORT = 48010
    const val UDP_VIDEO_PORT = 47998
    const val UDP_CONTROL_PORT = 47999
    const val UDP_AUDIO_PORT = 48000

    // CORRETTO dal crash reale: Moonlight PairingManager esige 4 componenti
    // ("Malformed server version field: 0.23.1"). 7.1.431.0 e' il livello GFE
    // di riferimento per GameStream.
    const val APP_VERSION = "7.1.431.0"
    const val GFE_VERSION = "7.1.431.0"

    private const val PREFS = "droidstream_host"

    fun uniqueId(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var id = p.getString("uniqueid", null)
        if (id.isNullOrEmpty()) {
            id = UUID.randomUUID().toString()
            p.edit().putString("uniqueid", id).apply()
        }
        return id
    }

    fun hostname(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var n = p.getString("hostname", null)
        if (n.isNullOrEmpty()) {
            n = "DroidStream-" + Build.MODEL.replace(" ", "-")
            p.edit().putString("hostname", n).apply()
        }
        return n
    }
}
