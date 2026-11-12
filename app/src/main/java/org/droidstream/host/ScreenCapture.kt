package org.droidstream.host

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer

class ScreenCapture(private val context: Context, private val params: LaunchHandler.LaunchParams) {
    private val TAG = "ScreenCapture"
    private var mediaProjection: android.media.projection.MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var surface: Surface? = null
    private var videoSocket: DatagramSocket? = null
    private var remoteAddress: InetSocketAddress? = null
    @Volatile private var running = false
    private var encoderThread: Thread? = null

    companion object {
        const val REQUEST_CODE_MEDIA_PROJECTION = 1001
    }

    fun start() {
        Log.i(TAG, "ScreenCapture initialized: ${params.width}x${params.height}@${params.fps}fps")
    }

    fun startWithProjection(resultCode: Int, data: Intent) {
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        
        if (mediaProjection == null) {
            Log.e(TAG, "Failed to get MediaProjection")
            return
        }

        setupEncoder()
        setupVideoSocket()
        startVirtualDisplay()
        startEncoding()
    }

    private fun setupEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, params.width, params.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, params.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, params.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            
            // Profilo e livello per compatibilità
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
            }
        }

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = encoder?.createInputSurface()
        encoder?.start()
        
        Log.i(TAG, "Encoder configured: ${params.width}x${params.height}@${params.fps}fps bitrate=${params.bitrate}")
    }

    private fun setupVideoSocket() {
        videoSocket = DatagramSocket()
        // L'indirizzo remoto è quello del client Moonlight (dal SETUP RTSP)
        // Per ora usiamo un placeholder, verrà aggiornato quando riceviamo il SETUP
        remoteAddress = InetSocketAddress("192.168.1.239", 47998)
        Log.i(TAG, "Video socket ready, target: $remoteAddress")
    }

    private fun startVirtualDisplay() {
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "DroidStream",
            params.width,
            params.height,
            320, // DPI
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )
        Log.i(TAG, "VirtualDisplay created")
    }

    private fun startEncoding() {
        running = true
        encoderThread = Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            var frameIndex = 0
            
            while (running) {
                val outputIndex = encoder?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
                
                if (outputIndex >= 0) {
                    val outputBuffer = encoder?.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        
                        val data = ByteArray(bufferInfo.size)
                        outputBuffer.get(data)
                        
                        // Invia al packetizer nativo
                        val isKeyframe = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        NativeBridge.sendVideoFrame(data, frameIndex, isKeyframe, remoteAddress?.address ?: "192.168.1.239", remoteAddress?.port ?: 47998)
                        
                        frameIndex++
                    }
                    encoder?.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
        encoderThread?.start()
        Log.i(TAG, "Starting RTP stream...")
    }

    fun stop() {
        running = false
        encoderThread?.join(1000)
        virtualDisplay?.release()
        surface?.release()
        encoder?.stop()
        encoder?.release()
        videoSocket?.close()
        mediaProjection?.stop()
        Log.i(TAG, "ScreenCapture stopped")
    }
}
