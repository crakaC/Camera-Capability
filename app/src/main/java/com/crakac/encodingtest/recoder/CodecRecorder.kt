package com.crakac.encodingtest.recoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.crakac.encodingtest.util.LOG
import com.crakac.encodingtest.util.Util
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch

class CodecRecorder(
    width: Int,
    height: Int,
    fps: Int,
    bitrate: Int,
    codec: String? = null,
    private val onSave: ((Uri) -> Unit)?
) : Recorder, Muxer {

    companion object {
        private const val EXPECTED_TRACKS = 2
    }

    private val videoEncoder = VideoEncoder(width, height, fps, bitrate, codec, this)
    private val audioEncoder = AudioEncoder(muxer = this)

    private lateinit var muxer: MediaMuxer

    private var outputUri: Uri? = null

    private var orientation = 0

    override fun getSurface() = videoEncoder.surface

    private lateinit var trackLatch: CountDownLatch
    private var currentTracks = 0

    override fun addTrack(format: MediaFormat): Int {
        val trackId = muxer.addTrack(format)
        synchronized(this) {
            currentTracks++
            if (currentTracks == EXPECTED_TRACKS) {
                muxer.start()
            }
        }
        trackLatch.countDown()
        trackLatch.await()
        return trackId
    }

    override fun writeData(trackId: Int, data: ByteBuffer, info: MediaCodec.BufferInfo) {
        muxer.writeSampleData(trackId, data, info)
    }

    override fun stopMuxer() {
        synchronized(this) {
            currentTracks--
            if (currentTracks == 0) {
                onEncodeFinish()
            }
        }
    }

    private fun onEncodeFinish() {
        LOG("CodecRecorder", "CodecRecorder Finished")
        outputUri?.let {
            onSave?.invoke(it)
        }
        muxer.stop()
        muxer.release()
    }

    override fun prepare(orientation: Int) {
        this.orientation = orientation
        videoEncoder.prepare()
        audioEncoder.prepare()
        trackLatch = CountDownLatch(EXPECTED_TRACKS)
        currentTracks = 0
    }

    override fun start() {
        outputUri = Util.createNewFileUri()
        val fd = Util.getFileDescriptor(outputUri!!)!!
        muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(orientation)

        videoEncoder.start()
        audioEncoder.start()
    }

    override fun stop() {
        videoEncoder.stop()
        audioEncoder.stop()
    }

    override fun release() {
        videoEncoder.release()
        audioEncoder.release()
    }

}