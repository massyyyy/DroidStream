package org.droidstream.host

import android.content.Context
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class ScreenCapture(private val ctx: Context, private val params: LaunchHandler.LaunchParams) {
    private val TAG = "ScreenCapture"
    private var isRunning = false
    private var isSending = false
    private var videoSocket: DatagramSocket? = null
    private var clientAddress: InetAddress? = null
    private var clientPort: Int = 47998

    fun start() {
        isRunning = true
        Log.i(TAG, "ScreenCapture initialized: ${params.width}x${params.height}@${params.fps}fps")
        try {
            videoSocket = DatagramSocket()
            // NOTA: In M3b prenderemo l'IP reale dal comando SETUP di RTSP.
            // Per M3a usiamo un placeholder per testare la connettività.
            clientAddress = InetAddress.getByName("192.168.1.239") 
            clientPort = 47998
            Log.i(TAG, "Video socket ready, target: ${clientAddress}:${clientPort}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init video socket", e)
        }
    }

    fun startSending() {
        if (isSending) return
        isSending = true
        Log.i(TAG, "Starting RTP stream...")
        Thread { sendDummyRtp() }.apply { isDaemon = true }.start()
    }

    private fun sendDummyRtp() {
        var seq = 0
        var timestamp = 0L
        val fps = params.fps.coerceAtLeast(1)
        val frameTimeUs = 1000000L / fps
        
        while (isRunning && isSending) {
            try {
                // Payload H.264 fittizio (SPS + PPS + IDR) per soddisfare il depacketizer di Moonlight
                val nalu = byteArrayOf(
                    0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1f,
                    0x00, 0x00, 0x00, 0x01, 0x68, 0xce, 0x3c, 0x80,
                    0x00, 0x00, 0x00, 0x01, 0x65, 0xb8, 0x00, 0x00
                )
                
                // Header NV_VIDEO_PACKET semplificato
                val header = ByteArray(12)
                header[0] = 0x01 // FLAG_SOF | FLAG_EOF
                header[4] = (seq shr 24).toByte()
                header[5] = (seq shr 16).toByte()
                header[6] = (seq shr 8).toByte()
                header[7] = seq.toByte()
                
                val payload = header + nalu
                
                // RTP Header (12 byte)
                val rtpHeader = ByteArray(12)
                rtpHeader[0] = 0x80.toByte() // V=2
                rtpHeader[1] = 0x60.toByte() // PT=96
                rtpHeader[2] = (seq shr 8).toByte()
                rtpHeader[3] = seq.toByte()
                val ts = (timestamp / 1000 * 90).toInt()
                rtpHeader[4] = (ts shr 24).toByte()
                rtpHeader[5] = (ts shr 16).toByte()
                rtpHeader[6] = (ts shr 8).toByte()
                rtpHeader[7] = ts.toByte()
                rtpHeader[8] = 0x01; rtpHeader[9] = 0x02; rtpHeader[10] = 0x03; rtpHeader[11] = 0x04
                
                val packetData = rtpHeader + payload
                val packet = DatagramPacket(packetData, packetData.size, clientAddress, clientPort)
                videoSocket?.send(packet)
                
                Log.d(TAG, "Sent RTP seq=$seq")
                seq = (seq + 1) and 0xFFFF
                timestamp += frameTimeUs
                
                Thread.sleep(frameTimeUs / 1000)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending RTP", e)
                break
            }
        }
    }

    fun stop() {
        isRunning = false
        isSending = false
        videoSocket?.close()
        videoSocket = null
        Log.i(TAG, "ScreenCapture stopped")
    }
}
