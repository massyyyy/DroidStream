package org.droidstream.host

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.net.DatagramSocket
import java.net.InetSocketAddress

class ScreenCapture(private val context: Context, private val params: LaunchHandler.LaunchParams) {

    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var surface: Surface? = null
    private var videoSocket: DatagramSocket? = null
    private var remoteAddress: InetSocketAddress? = null
    @Volatile private var running = false
    private var encoderThread: Thread? = null

    private var streamWidth = params.width
    private var streamHeight = params.height
    private var streamFps = params.fps

    fun updateClientAddress(ip: String) {
        if (ip.isEmpty() || ip == "127.0.0.1") return
        remoteAddress = InetSocketAddress(ip, HostIdentity.UDP_VIDEO_PORT)
        Log.i(TAG, "Aggiornato indirizzo client video: $remoteAddress")
    }

    fun start() {
        Log.i(TAG, "ScreenCapture initialized: ${params.width}x${params.height}@${params.fps}fps")
        if (videoSocket == null) {
            videoSocket = DatagramSocket()
            remoteAddress = InetSocketAddress(params.clientIp, HostIdentity.UDP_VIDEO_PORT)
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
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        if (ProjectionHolder.mediaProjection == null) {
            val data = ProjectionHolder.data ?: return
            ProjectionHolder.mediaProjection = mpm.getMediaProjection(ProjectionHolder.resultCode, data)
        }
        val mp = ProjectionHolder.mediaProjection
        if (mp == null) {
            Log.e(TAG, "MediaProjection null")
            return
        }
        NativeBridge.resetVideoPacketizer()
        setupEncoder()
        startVirtualDisplay(mp)
        startEncoding()
        running = true
        Log.i(TAG, "Cattura schermo avviata")
    }

    private fun setupEncoder() {
        // Su chip Snapdragon entry-level (max 244.800 blocchi/sec), 1080p60 supera il limite hardware.
        // Se la risoluzione richiesta supera 720p, limitiamo il framerate a 30fps per evitare blocchi dell'encoder.
        if (streamWidth > 1280 || streamHeight > 720) {
            if (streamFps > 30) streamFps = 30
        }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, streamWidth, streamHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, params.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, streamFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // Keyframe ogni 1 secondo
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            try {
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, (1_000_000L / streamFps))
            } catch (e: Exception) {
                try {
                    setLong("repeat-previous-frame-after", (1_000_000L / streamFps))
                } catch (e2: Exception) {}
            }
        }
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = encoder?.createInputSurface()
        encoder?.start()
        
        // Richiedi keyframe immediato
        val bundle = Bundle()
        bundle.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        encoder?.setParameters(bundle)
        
        Log.i(TAG, "Encoder configurato ${streamWidth}x${streamHeight}@${streamFps}fps")
    }

    private fun startVirtualDisplay(mp: android.media.projection.MediaProjection) {
        virtualDisplay = mp.createVirtualDisplay(
            "DroidStream",
            streamWidth, streamHeight, 320,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null)
    }

    private fun startEncoding() {
        val thread = Thread({
            val info = MediaCodec.BufferInfo()
            var frameIndex = 0
            val timestampIncrement = 90000L / streamFps 
            var spsPps: ByteArray? = null
            
            while (running) {
                val idx = encoder?.dequeueOutputBuffer(info, 10000) ?: -1
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // Continua ad attendere
                } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.i(TAG, "Encoder: formato output cambiato: " + encoder?.outputFormat)
                } else if (idx >= 0) {
                    val buf = encoder?.getOutputBuffer(idx)
                    if (buf != null && info.size > 0) {
                        val data = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.get(data)
                        
                        val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        
                        if (isConfig) {
                            spsPps = data
                            Log.i(TAG, "Cached SPS/PPS config: ${data.size} bytes")
                        } else {
                            val frameBytes = if (isKey && spsPps != null) {
                                // Moonlight richiede che ogni IDR inizi con SPS (Annex-B 00 00 00 01 67)
                                if (data.size > 4 && (data[4].toInt() and 0x1F) == 7) {
                                    data
                                } else {
                                    spsPps!! + data
                                }
                            } else {
                                data
                            }
                            
                            val rtpTimestamp = (frameIndex * timestampIncrement).toInt()
                            val targetHost = remoteAddress?.address?.hostAddress ?: params.clientIp
                            val targetPort = remoteAddress?.port ?: HostIdentity.UDP_VIDEO_PORT
                            
                            NativeBridge.sendVideoFrame(
                                frameBytes, frameIndex, isKey, rtpTimestamp,
                                targetHost, targetPort)
                            if (frameIndex % 60 == 0) {
                                Log.i(TAG, "Frame inviato: #$frameIndex (size=${frameBytes.size}, key=$isKey) a $targetHost:$targetPort")
                            }
                            frameIndex++
                        }
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
        try { encoderThread?.join(500) } catch (e: Exception) {}
        try { virtualDisplay?.release() } catch (e: Exception) {}
        virtualDisplay = null
        try { surface?.release() } catch (e: Exception) {}
        surface = null
        try {
            encoder?.stop()
            encoder?.release()
        } catch (e: Exception) {}
        encoder = null
        try { videoSocket?.close() } catch (e: Exception) {}
        videoSocket = null
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
