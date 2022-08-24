package com.crakac.encodingtest.recoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.crakac.encodingtest.util.LOG
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

class VideoEncoder(
    width: Int,
    height: Int,
    fps: Int,
    bitrate: Int,
    codec: String? = null,
    muxer: Muxer
) : Encoder {

    companion object {
        private const val TAG = "VideoEncoder"
        private const val MIME_TYPE = "video/avc"
        private const val TIMEOUT_MICRO_SEC = 50_000L // 50ms
    }

    private val scope = CoroutineScope(
        Executors.newSingleThreadExecutor().asCoroutineDispatcher() +
                CoroutineName("VideoEncoder")
    )

    private val muxerRef = WeakReference(muxer)
    private val muxer: Muxer get() = muxerRef.get()!!

    private var isEncoding = false

    private val codec: MediaCodec = if (codec != null) {
        MediaCodec.createByCodecName(codec)
    } else {
        MediaCodec.createEncoderByType(MIME_TYPE)
    }

    private val format: MediaFormat =
        MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
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

    val surface: Surface = MediaCodec.createPersistentInputSurface()

    private var isFirstPrepare = true

    init {
        this.codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        this.codec.setInputSurface(surface)
    }

    fun prepare() {
        if (isFirstPrepare) {
            isFirstPrepare = false
            return
        }
        codec.reset()
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.setInputSurface(surface)
    }

    override fun start() {
        isEncoding = true
        codec.start()
        LOG(TAG, "ENCODING START")
        drain()
    }

    private fun drain() = scope.launch {
        val bufferInfo = MediaCodec.BufferInfo()
        var trackId = -1
        while (isEncoding) {
            val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_MICRO_SEC)
            if (outputBufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                continue
            } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                LOG(TAG, "OUTPUT_FORMAT_CHANGED")
                trackId = muxer.addTrack(codec.outputFormat)
            } else {
                val encodedData = codec.getOutputBuffer(outputBufferId)
                    ?: throw RuntimeException("encodedData is null")
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    bufferInfo.size = 0
                }
                if (bufferInfo.size > 0) {
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeData(trackId, encodedData, bufferInfo)
                }
                codec.releaseOutputBuffer(outputBufferId, false)
            }
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                throw RuntimeException("Unexpected EOS in VideoEncoder")
            }
        }
        codec.stop()
        LOG(TAG, "VideoEncoder FINISHED")
        muxer.stopMuxer()
    }

    override fun stop() {
        if (isEncoding) {
            isEncoding = false
            /*
             * If encoder.signalEndOfInputStream() is called even once,
             * every BufferInfo from MediaCodec#dequeueOutputBuffer()
             * flagged EOS in the future even if reconfigure encoder
             * as long as it keeps using the same surface.
             */
        }
    }

    override fun isEncoding() = isEncoding

    fun release() {
        surface.release()
        codec.stop()
        codec.release()
    }
}