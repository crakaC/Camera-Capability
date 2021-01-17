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
    private var muxer: MediaMuxer? = null

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
    private var isFirstPrepare = true
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
    }

    fun prepare() {
        if(isFirstPrepare) {
            isFirstPrepare = false
            return
        }
        encoder.reset()
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.setInputSurface(surface)
    }

    override fun start() {
        if (muxer == null) {
            throw IllegalStateException("set muxer before start()")
        }
        isEncoding = true
        encoder.start()
        Log.v(TAG, "************ENCODER START************")
        drain()
    }

    override fun stop() {
        if (isEncoding) {
            encoder.signalEndOfInputStream()
        }
    }

    override fun isEncoding() = isEncoding

    private var bufferCount = 0L
    private fun drain() {
        var trackIndex = -1
        bufferCount = 0
        var bufferId = 0
        scope.launch {
            while (isEncoding) {
                bufferId = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_MICRO_SEC)
                if (bufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue
                } else if (bufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.v(TAG, "OUTPUT FORMAT CHANGED")
                    val format = encoder.outputFormat
                    trackIndex = muxer!!.addTrack(format)
                    muxer!!.start()
                } else {
                    val encodedData = encoder.getOutputBuffer(bufferId)
                        ?: throw RuntimeException("VideoEncoder: encodedData was null")

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size > 0) {
                        bufferCount++
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer!!.writeSampleData(trackIndex, encodedData, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(bufferId, false)
                }

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    Log.v(TAG, "*************EOS***************")
                    isEncoding = false
                    listener?.onEncodeEnd()
                    break
                }
            }
            Log.v(TAG, "**********STOP MUXER**********")
            muxer?.stop()
            muxer?.release()
            muxer = null
            Log.v(TAG, "**********STOP ENCODER**********")
            encoder.stop()
            Log.v(TAG, "*****ENCODING FINISHED, $bufferCount buffers*****")
        }
    }

    fun release() {
        surface.release()
        encoder.stop()
        encoder.release()
    }
}