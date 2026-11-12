package org.droidstream.host

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.SystemClock
import java.nio.ByteBuffer
import java.util.Arrays

object EncoderBench {

    fun run(width: Int = 720, height: Int = 1280, fps: Int = 30,
            bitrate: Int = 5000000, frames: Int = 60): String {
        val sb = StringBuilder()
        sb.append("\n--- BENCHMARK ENCODER ").append(width).append("x").append(height)
          .append("@").append(fps).append(" ").append(bitrate / 1000).append(" kbps, ")
          .append(frames).append(" frame ---\n")

        val mime = "video/avc"
        var hw: MediaCodecInfo? = null
        var sw: MediaCodecInfo? = null
        for (ci in MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos) {
            if (!ci.isEncoder || !ci.supportedTypes.contains(mime)) continue
            val n = ci.name.lowercase()
            if (n.contains("google") || n.contains(".sw.")) { if (sw == null) sw = ci }
            else if (hw == null) hw = ci
        }
        val info = hw ?: sw ?: return sb.append("nessun encoder H.264\n").toString()
        sb.append("encoder: ").append(info.name).append(if (hw == null) " (SOFTWARE)" else " (hardware)").append("\n")

        val formats = try { info.getCapabilitiesForType(mime).colorFormats } catch (e: Exception) { IntArray(0) }
        val colorFmt = when {
            formats.contains(21) -> 21
            formats.contains(19) -> 19
            else -> -1
        }
        if (colorFmt < 0) {
            return sb.append("nessun formato YUV a buffer: ").append(formats.joinToString(",")).append("\n").toString()
        }
        sb.append("colorFormat: ").append(colorFmt).append(if (colorFmt == 21) " NV12" else " I420").append("\n")

        var codec: MediaCodec? = null
        try {
            codec = MediaCodec.createByCodecName(info.name)
            val fmt = MediaFormat.createVideoFormat(mime, width, height)
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFmt)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            fmt.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            fmt.setInteger(MediaFormat.KEY_PRIORITY, 0)
            sb.append("config: CBR, priority=0, GOP 2s (effetto priority DA VERIFICARE)\n")
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val frameUs = 1000000L / fps
            val frameSize = width * height * 3 / 2
            val pending = HashMap<Long, Long>()
            val lat = ArrayList<Long>()
            val bi = MediaCodec.BufferInfo()
            var sent = 0; var received = 0; var bytes = 0L; var keys = 0
            val start = SystemClock.elapsedRealtimeNanos()

            while (received < frames) {
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1000000
                if (elapsedMs > 25000) { sb.append("TIMEOUT dopo ").append(elapsedMs).append(" ms\n"); break }

                if (sent < frames) {
                    val targetUs = sent * frameUs
                    val nowUs = (SystemClock.elapsedRealtimeNanos() - start) / 1000
                    if (nowUs < targetUs) Thread.sleep((targetUs - nowUs) / 1000)
                    val ii = codec.dequeueInputBuffer(2000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)
                        if (buf != null) {
                            buf.clear()
                            fill(buf, width, height, colorFmt, sent)
                            val pts = sent * frameUs
                            pending[pts] = SystemClock.elapsedRealtimeNanos()
                            codec.queueInputBuffer(ii, 0, frameSize, pts, 0)
                            sent++
                        }
                    }
                }

                val oi = codec.dequeueOutputBuffer(bi, 2000)
                if (oi >= 0) {
                    val isConfig = (bi.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (!isConfig && bi.size > 0) {
                        val t = pending.remove(bi.presentationTimeUs)
                        if (t != null) lat.add((SystemClock.elapsedRealtimeNanos() - t) / 1000000)
                        bytes += bi.size
                        if ((bi.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) keys++
                        received++
                    }
                    codec.releaseOutputBuffer(oi, false)
                }
            }

            val totalMs = (SystemClock.elapsedRealtimeNanos() - start) / 1000000
            if (lat.isEmpty()) {
                sb.append("NESSUN frame prodotto (sent=").append(sent).append(")\n")
            } else {
                lat.sort()
                val p95 = lat[(lat.size * 95 / 100).coerceAtMost(lat.size - 1)]
                sb.append("frame: ").append(received).append("/").append(frames)
                  .append(" in ").append(totalMs).append(" ms, IDR=").append(keys).append("\n")
                sb.append("LATENZA ENCODE ms  min=").append(lat[0])
                  .append(" mediana=").append(lat[lat.size / 2])
                  .append(" p95=").append(p95)
                  .append(" max=").append(lat[lat.size - 1]).append("\n")
                sb.append("bitrate reale: ").append(bytes * 8 * 1000 / (totalMs * 1000)).append(" kbps\n")
            }
        } catch (e: Exception) {
            sb.append("ERRORE benchmark: ").append(e.javaClass.simpleName).append(": ").append(e.message).append("\n")
        } finally {
            try { codec?.stop() } catch (e: Exception) {}
            try { codec?.release() } catch (e: Exception) {}
        }
        return sb.toString()
    }

    private fun fill(buf: ByteBuffer, w: Int, h: Int, fmt: Int, index: Int) {
        val shift = (index * 4) and 0xFF
        val row = ByteArray(w)
        for (y in 0 until h) {
            Arrays.fill(row, ((y + shift) and 0xFF).toByte())
            buf.put(row)
        }
        if (fmt == 21) {
            val uv = ByteArray(w * h / 2); Arrays.fill(uv, 128.toByte()); buf.put(uv)
        } else {
            val u = ByteArray(w * h / 4); Arrays.fill(u, 128.toByte()); buf.put(u); buf.put(u)
        }
    }
}
