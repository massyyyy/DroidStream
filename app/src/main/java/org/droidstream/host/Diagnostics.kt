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
        sb.append("=== DroidStream M0 - diagnostica host ===\n")

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

        sb.append("\n--- ROOT (indizio, non prova) ---\n")
        val suPaths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
                             "/system/bin/magisk", "/data/adb/magisk")
        val found = suPaths.filter { File(it).exists() }
        sb.append("su trovati: ").append(if (found.isEmpty()) "nessuno" else found.joinToString(", ")).append("\n")
        sb.append("Build.TAGS: ").append(Build.TAGS).append("\n")

        sb.append("\n--- ENCODER H.264 ---\n")
        try {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            var n = 0
            for (ci in list.codecInfos) {
                if (!ci.isEncoder) continue
                if (!ci.supportedTypes.contains("video/avc")) continue
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
                          .append(" fps ").append(vc.supportedFrameRates.upper)
                          .append(" bitrate ").append(vc.bitrateRange.lower).append("..")
                          .append(vc.bitrateRange.upper).append("\n")
                    }
                    sb.append("   profili: ").append(caps.profileLevels.size)
                    val feats = mutableListOf<String>()
                    if (caps.isFeatureSupported("low-latency")) feats.add("low-latency")
                    if (caps.isFeatureSupported("intra-refresh")) feats.add("intra-refresh")
                    sb.append("   feature: ").append(if (feats.isEmpty()) "nessuna dichiarata" else feats.joinToString(", ")).append("\n")
                } catch (e: Exception) { sb.append("   caps: errore ").append(e.message).append("\n") }
            }
            if (n == 0) sb.append("NESSUN encoder H.264 trovato\n")
        } catch (e: Exception) { sb.append("codec: errore ").append(e.message).append("\n") }

        sb.append("\n--- NATIVE (NDK) ---\n")
        sb.append(try { NativeBridge.info() } catch (e: Throwable) { "ERRORE native: " + e.message }).append("\n")

        sb.append("\n=== fine ===\n")
        return sb.toString()
    }
}