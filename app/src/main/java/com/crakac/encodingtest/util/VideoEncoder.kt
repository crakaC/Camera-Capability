package com.crakac.encodingtest.util

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

class VideoEncoder : Encoder {
    interface VideoEncoderListener{
        fun onDataEncoded(data: ByteArray){}
    }
    companion object {
        const val MIME_TYPE = "video/avc"
        const val TIMEOUT_MICRO_SEC = 1_000L // 1ms
    }

    private var listener: VideoEncoderListener? = null
    fun setVideoEncoderListener(listener: VideoEncoderListener){
        this.listener = listener
    }
    private var isEncoding = false
    private var encoder: MediaCodec? = null
    private var surface: Surface? = null
    fun getSurface() = surface
    private var bufferInfo = MediaCodec.BufferInfo()

    fun prepare(width: Int, height: Int, bitrate: Int) {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_WIDTH, width)
            setInteger(MediaFormat.KEY_HEIGHT, height)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
        }
        encoder = MediaCodec.createEncoderByType(MIME_TYPE)
        encoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = encoder!!.createInputSurface()
    }

    override fun start() {
        if (encoder == null) {
            throw IllegalStateException("call prepare() before start()")
        }
        encoder!!.start()
        isEncoding = true
        drain()
    }

    override fun stop() {
        if (isEncoding) {
            encoder?.signalEndOfInputStream()
        }
    }

    override fun isEncoding() = encoder != null && isEncoding

    private fun drain() {
        val encoderThread = HandlerThread("Encoder thread")
        val handler = Handler(encoderThread.looper)
        encoderThread.start()
        handler.post {
            while (isEncoding) {
                if (encoder == null) return@post
                val encoder = encoder!!
                val bufferId = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_MICRO_SEC)
                if(bufferId == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue
                } else if(bufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                    val newFormat = encoder.outputFormat
                    val sps = newFormat.getByteBuffer("csd-0")!!
                    val pps = newFormat.getByteBuffer("csd-1")!!
                    val config = ByteArray(sps.limit() + pps.limit())
                    sps.get(config, 0, sps.limit())
                    pps.get(config, sps.limit(), pps.limit())
                    // onVideoDataEncoded(config, config.length, 0)
                } else {
                    val encodedData = encoder.getOutputBuffer(bufferId) ?: continue
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    val data = ByteArray(bufferInfo.size)
                    encodedData.get(data, 0, bufferInfo.size)
                    encoder.releaseOutputBuffer(bufferId, false)
                    listener?.onDataEncoded(data)
                }

                if(bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0){
                    break
                }
            }
            release()
        }
    }

    private fun release(){
        isEncoding = false
        encoder?.stop()
        encoder?.release()
        encoder = null
    }
}