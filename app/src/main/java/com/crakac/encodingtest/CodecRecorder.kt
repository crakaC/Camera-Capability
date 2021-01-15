package com.crakac.encodingtest

import android.media.MediaMuxer
import android.net.Uri
import com.crakac.encodingtest.util.Util

class CodecRecorder(
    width: Int,
    height: Int,
    fps: Int,
    bitrate: Int,
    codec: String? = null,
    private val onSave: ((Uri) -> Unit)?
) : Recorder, VideoEncoder.VideoEncoderListener {

    private var videoEncoder = VideoEncoder(width, height, fps, bitrate, codec)
    private lateinit var muxer: MediaMuxer
    private var outputUri: Uri? = null
    private var orientation = 0
    override fun getSurface() = videoEncoder.surface

    override fun prepare(orientation: Int) {
        this.orientation = orientation
        videoEncoder.prepare()
    }

    override fun start() {
        outputUri = Util.createNewFileUri()
        val fd = Util.getFileDescriptor(outputUri!!)!!
        muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(orientation)
        videoEncoder.setMuxer(muxer)
        videoEncoder.setVideoEncoderListener(this)
        videoEncoder.start()
    }

    override fun stop() {
        videoEncoder.stop()
    }

    override fun release() {
        videoEncoder.release()
    }

    override fun onDataEncoded(data: ByteArray) {
    }

    override fun onEncodeEnd() {
        outputUri?.let {
            onSave?.invoke(it)
        }
        videoEncoder.setVideoEncoderListener(null)
    }
}