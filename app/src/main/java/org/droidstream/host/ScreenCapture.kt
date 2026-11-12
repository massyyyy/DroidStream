package org.droidstream.host

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjectionManager
import android.util.Log
import android.view.Surface
import java.net.DatagramSocket
import java.net.InetSocketAddress

class ScreenCapture(private val context: Context, private val params: LaunchHandler.LaunchParams) {

    private var mediaProjection: android.media.projection.MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var surface: Surface? = null
    private var videoSocket: DatagramSocket? = null
    private var remoteAddress: InetSocketAddress? = null
    @Volatile private var running = false
    private var encoderThread: Thread? = null

    fun start() {
        Log.i(TAG, "ScreenCapture initialized: ${params.width}x${params.height}@${params.fps}fps")
        if (videoSocket == null) {
            videoSocket = DatagramSocket()
            remoteAddress = InetSocketAddress("192.168.1.239", 47998)
            Log.i(TAG, "Video socket ready, target: $remoteAddress")
        }
        if (ProjectionHolder.has()) {
            begin()
        } else {
            pending = this
            ProjectionActivity.start(context)
            Log.i(TAG, "In attesa del consenso di registrazione schermo...")
        }
    }

    fun startWithProjection(resultCode: Int, data: Intent) {
        ProjectionHolder.resultCode = resultCode
        ProjectionHolder.data = data
        begin()
    }

    fun startSending() {
        Log.i(TAG, "startSending() richiesto")
        if (!running) start()
    }

    fun begin() {
        if (running) return
        val data = ProjectionHolder.data ?: return
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(ProjectionHolder.resultCode, data)
        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection null")
            return
        }
        setupEncoder()
        startVirtualDisplay()
        startEncoding()
        running = true
        Log.i(TAG, "Cattura schermo avviata")
    }

    private fun setupEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, params.width, params.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, params.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, params.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel32)
        }
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = encoder?.createInputSurface()
        encoder?.start()
        Log.i(TAG, "Encoder configurato ${params.width}x${params.height}@${params.fps}")
    }

    private fun startVirtualDisplay() {
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "DroidStream",
            params.width, params.height, 320,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null)
    }

    private fun startEncoding() {
        val thread = Thread({
            val info = MediaCodec.BufferInfo()
            var frameIndex = 0
            while (running) {
                val idx = encoder?.dequeueOutputBuffer(info, 10000) ?: -1
                if (idx >= 0) {
                    val buf = encoder?.getOutputBuffer(idx)
                    if (buf != null && info.size > 0) {
                        val data = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.get(data)
                        val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 ||
                                (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        NativeBridge.sendVideoFrame(
                            data, frameIndex, key,
                            remoteAddress?.address?.hostAddress ?: "192.168.1.239",
                            remoteAddress?.port ?: 47998)
                        frameIndex++
                    }
                    encoder?.releaseOutputBuffer(idx, false)
                }
            }
        }, "video-encoder")
        thread.isDaemon = true
        thread.start()
        encoderThread = thread
        Log.i(TAG, "Starting RTP stream...")
    }

    fun stop() {
        running = false
        if (pending === this) pending = null
        encoderThread?.join(500)
        virtualDisplay?.release()
        surface?.release()
        try {
            encoder?.stop()
            encoder?.release()
        } catch (e: Exception) {
        }
        videoSocket?.close()
        videoSocket = null
        mediaProjection?.stop()
        mediaProjection = null
        Log.i(TAG, "ScreenCapture stopped")
    }

    companion object {
        private const val TAG = "ScreenCapture"
        const val REQUEST_CODE_MEDIA_PROJECTION = 1001
        @Volatile
        var pending: ScreenCapture? = null
            private set

        fun onProjectionGranted() {
            pending?.begin()
        }
    }
}
