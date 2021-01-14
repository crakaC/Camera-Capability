package com.crakac.encodingtest

import android.media.MediaCodec
import android.media.MediaRecorder
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import com.crakac.encodingtest.util.Util
import java.io.File
import java.io.IOException

class DefaultMediaRecorder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val onSave: ((Uri) -> Unit)?
) : Recorder {
    val TAG: String = "DefaultMediaRecorder"
    private var recorder: MediaRecorder? = null
    private val recorderSurface by lazy {
        val surface = MediaCodec.createPersistentInputSurface()
        createMediaRecorder(surface, dummy = true).apply {
            prepare()
            release()
        }
        surface
    }
    private var outputUri: Uri? = null
    private var outputFd: ParcelFileDescriptor? = null
    private var isRecording = false

    override fun setOrientationHint(orientation: Int) {
        recorder?.setOrientationHint(orientation)
    }

    override fun getSurface() = recorderSurface

    override fun prepare() {
        recorder = createMediaRecorder(recorderSurface)
    }

    override fun start() {
        isRecording = true
        recorder?.prepare()
        recorder?.start()
    }

    override fun stop() {
        if(isRecording){
            isRecording = false
            recorder?.stop()
            save()
        }
    }

    override fun release() {
        recorder?.release()
        recorderSurface.release()
    }

    private fun createMediaRecorder(surface: Surface, dummy: Boolean = false) =
        MediaRecorder().apply {

            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)

            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

            if (dummy) {
                setOutputFile(File.createTempFile("dummy", null).absolutePath)
            } else {
                outputUri = Util.createNewFileUri()
                outputFd = Util.openFileDescriptor(outputUri!!)
                setOutputFile(outputFd!!.fileDescriptor)
            }

            setAudioChannels(1)
            setAudioEncodingBitRate(64 * 1024)
            setAudioSamplingRate(44100)
            if (fps > 0) setVideoFrameRate(fps)
            setVideoSize(width, height)
            setVideoEncodingBitRate(5 * 1024 * 1024)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.HE_AAC)
            setInputSurface(surface)
        }

    private fun save() {
        try {
            outputFd?.close()
        } catch (e: IOException) {
            Log.e(TAG, e.message, e)
        }
        outputUri?.let {
            onSave?.invoke(it)
        }
    }
}