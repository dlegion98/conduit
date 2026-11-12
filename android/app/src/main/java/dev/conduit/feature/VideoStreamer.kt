package dev.conduit.feature

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import dev.conduit.net.Connection
import java.nio.ByteBuffer

/**
 * Hardware H.264 encoder with a Surface input. Encoded frames go to the Mac as
 * `flags | ptsUs | Annex-B` binary frames (see PROTOCOL.md).
 */
class VideoStreamer(
    private val conn: Connection,
    val width: Int,
    val height: Int,
    bitrate: Int,
    fps: Int,
    private val onError: (Exception) -> Unit,
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val inputSurface: Surface
    @Volatile private var running = true
    private val thread: Thread

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_LATENCY, 0)
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000) // keep frames flowing on a still screen
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Some encoders reject the low-latency keys; retry without them.
            format.removeKey(MediaFormat.KEY_LOW_LATENCY)
            format.removeKey(MediaFormat.KEY_LATENCY)
            codec.reset()
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        inputSurface = codec.createInputSurface()
        conn.send("video-format") { put("width", width); put("height", height) }
        codec.start()
        thread = Thread(::drain, "conduit-encoder").apply { start() }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val index = codec.dequeueOutputBuffer(info, 20_000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        val sps = f.getByteBuffer("csd-0")
                        val pps = f.getByteBuffer("csd-1")
                        if (sps != null && pps != null) send(FLAG_CONFIG, 0, concat(sps, pps))
                    }
                    index >= 0 -> {
                        val buf = codec.getOutputBuffer(index)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val data = ByteArray(info.size).also { buf.get(it) }
                            var flags = 0
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) flags = flags or FLAG_CONFIG
                            if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) flags = flags or FLAG_KEY
                            send(flags, info.presentationTimeUs, data)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (e: Exception) {
            if (running) {
                Log.w("Conduit", "encoder stream ended", e)
                onError(e)
            }
        }
    }

    private fun send(flags: Int, ptsUs: Long, data: ByteArray) {
        val packet = ByteBuffer.allocate(9 + data.size)
        packet.put(flags.toByte())
        packet.putLong(ptsUs)
        packet.put(data)
        conn.sendBinary(packet.array())
    }

    private fun concat(a: ByteBuffer, b: ByteBuffer): ByteArray {
        val out = ByteArray(a.remaining() + b.remaining())
        a.duplicate().get(out, 0, a.remaining())
        b.duplicate().get(out, a.remaining(), b.remaining())
        return out
    }

    /** Stops the encoder; leaves the connection open (it may be reused after a resize). */
    fun release() {
        running = false
        runCatching { thread.join(500) }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { inputSurface.release() }
    }

    companion object {
        const val FLAG_CONFIG = 1
        const val FLAG_KEY = 2

        /** Scales (w, h) so the long edge is at most [max], rounded down to multiples of 16. */
        fun fit(w: Int, h: Int, max: Int): Pair<Int, Int> {
            val scale = minOf(1.0, max.toDouble() / maxOf(w, h))
            val sw = ((w * scale).toInt() and 15.inv()).coerceAtLeast(16)
            val sh = ((h * scale).toInt() and 15.inv()).coerceAtLeast(16)
            return sw to sh
        }
    }
}
