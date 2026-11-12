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
                // Payload H.264 fittizio (SPS + PPS + IDR)
                val nalu = byteArrayOf(
                    0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 
                    0x67.toByte(), 0x42.toByte(), 0x00.toByte(), 0x1f.toByte(),
                    0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 
                    0x68.toByte(), 0xce.toByte(), 0x3c.toByte(), 0x80.toByte(),
                    0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 
                    0x65.toByte(), 0xb8.toByte(), 0x00.toByte(), 0x00.toByte()
                )
                
                // Header NV_VIDEO_PACKET semplificato (12 byte)
                val header = ByteArray(12)
                header[0] = 0x01.toByte() // FLAG_SOF | FLAG_EOF
                header[4] = ((seq shr 24) and 0xFF).toByte()
                header[5] = ((seq shr 16) and 0xFF).toByte()
                header[6] = ((seq shr 8) and 0xFF).toByte()
                header[7] = (seq and 0xFF).toByte()
                
                val payload = header + nalu
                
                // RTP Header (12 byte)
                val rtpHeader = ByteArray(12)
                rtpHeader[0] = 0x80.toByte() // V=2
                rtpHeader[1] = 0x60.toByte() // PT=96
                rtpHeader[2] = ((seq shr 8) and 0xFF).toByte()
                rtpHeader[3] = (seq and 0xFF).toByte()
                val ts = ((timestamp / 1000 * 90) and 0xFFFFFFFFL).toInt()
                rtpHeader[4] = ((ts shr 24) and 0xFF).toByte()
                rtpHeader[5] = ((ts shr 16) and 0xFF).toByte()
                rtpHeader[6] = ((ts shr 8) and 0xFF).toByte()
                rtpHeader[7] = (ts and 0xFF).toByte()
                rtpHeader[8] = 0x01.toByte()
                rtpHeader[9] = 0x02.toByte()
                rtpHeader[10] = 0x03.toByte()
                rtpHeader[11] = 0x04.toByte()
                
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
