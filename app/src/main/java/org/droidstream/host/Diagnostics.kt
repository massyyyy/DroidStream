package org.droidstream.host

import android.content.Context
import android.media.MediaCodecList
import android.net.wifi.WifiManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File

object Diagnostics {

    fun collect(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("=== DroidStream M0.5 - diagnostica host ===\n")

        val ver = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Exception) { "?" }
        sb.append("App: ").append(ver).append("\n")
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
          .append(" (").append(Build.DEVICE).append(")\n")
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT).append("\n")
        sb.append("ABI: ").append(Build.SUPPORTED_ABIS.joinToString(", ")).append("\n")
        sb.append("CPU cores: ").append(Runtime.getRuntime().availableProcessors()).append("\n")

        sb.append("\n--- DISPLAY ---\n")
        try {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val d = wm.defaultDisplay
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") d.getRealMetrics(dm)
            sb.append("Reale: ").append(dm.widthPixels).append("x").append(dm.heightPixels)
              .append(" @").append(d.refreshRate).append("Hz rot=").append(d.rotation).append("\n")
        } catch (e: Exception) { sb.append("display: errore ").append(e.message).append("\n") }

        sb.append("\n--- WI-FI ---\n")
        try {
            val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION") val info = wm.connectionInfo
            @Suppress("DEPRECATION") val freq = info.frequency
            val band = if (freq in 2400..2500) "2.4 GHz" else if (freq in 4900..5900) "5 GHz" else "sconosciuta"
            sb.append("Freq: ").append(freq).append(" MHz (").append(band).append(")")
              .append(" RSSI ").append(info.rssi).append(" dBm link ").append(info.linkSpeed).append(" Mbps\n")
        } catch (e: Exception) { sb.append("wifi: errore ").append(e.message).append("\n") }

        sb.append("\n--- ROOT ---\n")
        sb.append(rootProbe())

        sb.append("\n--- ENCODER H.264 ---\n")
        try {
            var n = 0
            for (ci in MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos) {
                if (!ci.isEncoder || !ci.supportedTypes.contains("video/avc")) continue
                n++
                sb.append("* ").append(ci.name)
                if (Build.VERSION.SDK_INT >= 29) {
                    sb.append(if (ci.isHardwareAccelerated) " [HW]" else if (ci.isSoftwareOnly) " [SW]" else " [?]")
                }
                sb.append("\n")
                try {
                    val caps = ci.getCapabilitiesForType("video/avc")
                    val vc = caps.videoCapabilities
                    if (vc != null) {
                        sb.append("   max ").append(vc.supportedWidths.upper).append("x")
                          .append(vc.supportedHeights.upper)
                          .append(" bitrate ").append(vc.bitrateRange.lower).append("..")
                          .append(vc.bitrateRange.upper).append("\n")
                    }
                    sb.append("   profile/level: ")
                    for (pl in caps.profileLevels) sb.append(pl.profile).append("/").append(pl.level).append(" ")
                    sb.append("\n")
                    sb.append("   colorFormats: ").append(caps.colorFormats.joinToString(",")).append("\n")
                } catch (e: Exception) { sb.append("   caps: errore ").append(e.message).append("\n") }
            }
            if (n == 0) sb.append("NESSUN encoder H.264 trovato\n")
        } catch (e: Exception) { sb.append("codec: errore ").append(e.message).append("\n") }

        sb.append("\n--- NATIVE (NDK) ---\n")
        sb.append(try { NativeBridge.info() } catch (e: Throwable) { "ERRORE native: " + e.message }).append("\n")

        sb.append(EncoderBench.run())

        sb.append("\n=== fine ===\n")
        val report = sb.toString()
        Logger.i(ctx, report)
        return report
    }

    private fun rootProbe(): String {
        val paths = listOf("/sbin/su", "/system/bin/su", "/system/xbin/su", "/su/bin/su")
        val found = paths.filter { File(it).exists() }
        if (found.isEmpty()) return "su non trovato: nessun root\n"
        return try {
            val p = Runtime.getRuntime().exec(arrayOf(found[0], "-c", "id"))
            val out = StringBuilder()
            val t = Thread {
                try { p.inputStream.bufferedReader().forEachLine { out.append(it).append(" ") } } catch (e: Exception) {}
            }
            t.isDaemon = true
            t.start()
            t.join(5000)
            if (out.contains("uid=0")) "ROOT CONFERMATO: " + out.toString().trim() + "\n"
            else "su presente (" + found.joinToString(",") + ") ma risposta: '" + out.toString().trim() + "'\n"
        } catch (e: Exception) { "esecuzione su fallita: " + e.message + "\n" }
    }
}
