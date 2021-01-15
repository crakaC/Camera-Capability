package com.crakac.encodingtest

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import com.crakac.encodingtest.util.Encoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class VideoEncoder(
    width: Int,
    height: Int,
    fps: Int,
    bitrate: Int,
    codec: String? = null
) : Encoder {
    interface VideoEncoderListener {
        fun onDataEncoded(data: ByteArray) {}
        fun onEncodeEnd() {}
    }

    companion object {
        const val TAG = "VideoEncoder"
        const val MIME_TYPE = "video/avc"
        const val TIMEOUT_MICRO_SEC = 50_000L // 50ms
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    private var listener: VideoEncoderListener? = null
    private lateinit var muxer: MediaMuxer

    fun setVideoEncoderListener(listener: VideoEncoderListener?) {
        this.listener = listener
    }

    fun setMuxer(muxer: MediaMuxer) {
        this.muxer = muxer
    }

    private var isEncoding = false
    private val encoder: MediaCodec
    private val format: MediaFormat

    val surface: Surface = MediaCodec.createPersistentInputSurface()

    private var bufferInfo = MediaCodec.BufferInfo()

    init {
        format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_WIDTH, width)
            setInteger(MediaFormat.KEY_HEIGHT, height)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 2f)
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
        }
        encoder = if (codec != null) {
            MediaCodec.createByCodecName(codec)
        } else {
            MediaCodec.createEncoderByType(MIME_TYPE)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.setInputSurface(surface)
        encoder.reset()
    }

    fun prepare() {
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.setInputSurface(surface)
    }

    override fun start() {
        if (!::muxer.isInitialized) {
            throw IllegalStateException("set muxer before start()")
        }
        isEncoding = true
        encoder.start()
        Log.i(TAG, "************ENCODER START************")
        drain()
    }

    override fun stop() {
        if (isEncoding) {
            encoder.signalEndOfInputStream()
        }
    }

    override fun isEncoding() = isEncoding

    private var frameCount = 0L
    private fun drain() {
        var trackIndex = -1
        frameCount = 0
        scope.launch {
            while (isEncoding) {
                val bufferId = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_MICRO_SEC)
                if (bufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue
                } else if (bufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.i(TAG, "OUTPUT FORMAT CHANGED")
                    val format = encoder.outputFormat
                    trackIndex = muxer.addTrack(format)
                    muxer.start()
                } else {
                    val encodedData = encoder.getOutputBuffer(bufferId)
                        ?: throw RuntimeException("VideoEncoder: encodedData was null")

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size > 0) {
                        frameCount++
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
//                        val data = ByteArray(bufferInfo.size)
//                        encodedData.get(data, 0, bufferInfo.size)
//                        listener?.onDataEncoded(data)
                    }
                    encoder.releaseOutputBuffer(bufferId, false)
                }

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    Log.i(TAG, "*************EOS***************")
                    isEncoding = false
                    listener?.onEncodeEnd()
                    break
                }
            }
            encoder.stop()
            muxer.stop()
            muxer.release()
            Log.i(TAG, "ENCODING FINISHED, $frameCount frames")
        }
    }

    fun release() {
        surface.release()
        encoder.stop()
        encoder.release()
    }
}