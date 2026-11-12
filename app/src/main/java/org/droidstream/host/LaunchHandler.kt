package org.droidstream.host

import android.content.Context
import android.util.Log

object LaunchHandler {
    private const val TAG = "LaunchHandler"
    
    data class LaunchParams(
        val appId: Int,
        val width: Int,
        val height: Int,
        val fps: Int,
        val rikey: ByteArray,
        val rikeyId: Int,
        val bitrate: Int = 5000000
    )
    
    @Volatile
    var activeSession: LaunchParams? = null
        private set
    
    var rtspServer: RtspServer? = null
    var screenCapture: ScreenCapture? = null

    fun handleLaunch(ctx: Context, query: Map<String, String>): Pair<Int, String> {
        val appId = query["appid"]?.toIntOrNull() ?: return errorXml(400, "Missing appid")
        val mode = query["mode"] ?: return errorXml(400, "Missing mode")
        val rikey = query["rikey"] ?: return errorXml(400, "Missing rikey")
        val rikeyId = query["rikeyid"]?.toIntOrNull() ?: return errorXml(400, "Missing rikeyid")
        
        val parts = mode.split("x")
        if (parts.size != 3) return errorXml(400, "Invalid mode format")
        
        val width = parts[0].toIntOrNull() ?: return errorXml(400, "Invalid width")
        val height = parts[1].toIntOrNull() ?: return errorXml(400, "Invalid height")
        val fps = parts[2].toIntOrNull() ?: return errorXml(400, "Invalid fps")
        
        val keyBytes = try {
            hexToBytes(rikey)
        } catch (e: Exception) {
            return errorXml(400, "Invalid rikey hex")
        }
        
        activeSession = LaunchParams(
            appId = appId, width = width, height = height, fps = fps,
            rikey = keyBytes, rikeyId = rikeyId, bitrate = 5000000
        )
        
        Log.i(TAG, "Launch: ${width}x${height}@${fps}fps")
        
        if (rtspServer == null) {
            rtspServer = RtspServer(48010).apply { start() }
        }
        
        if (screenCapture == null) {
            screenCapture = ScreenCapture(ctx, activeSession!!).apply { start() }
        }
        
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<root status_code="200">
  <sessionUrl0>rtsp://192.168.1.218:48010</sessionUrl0>
  <gamesession>1</gamesession>
</root>"""
        return Pair(200, xml)
    }
    
    fun hexToBytes(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
    
    private fun errorXml(code: Int, msg: String): Pair<Int, String> {
        return Pair(code, """<?xml version="1.0" encoding="utf-8"?>
<root status_code="$code" status_message="$msg"/>""")
    }
    
    fun stop() {
        screenCapture?.stop()
        screenCapture = null
        rtspServer?.stop()
        rtspServer = null
        activeSession = null
    }
}
